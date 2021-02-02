package gov.cdc.prime.router.azure

import com.fasterxml.jackson.core.JsonFactory
import com.microsoft.azure.functions.ExecutionContext
import com.microsoft.azure.functions.HttpRequestMessage
import com.microsoft.azure.functions.HttpResponseMessage
import gov.cdc.prime.router.ClientSource
import gov.cdc.prime.router.OrganizationService
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.ReportId
import gov.cdc.prime.router.azure.db.Tables.ACTION
import gov.cdc.prime.router.azure.db.Tables.REPORT_FILE
import gov.cdc.prime.router.azure.db.Tables.REPORT_LINEAGE
import gov.cdc.prime.router.azure.db.enums.TaskAction
import gov.cdc.prime.router.azure.db.tables.pojos.Action
import gov.cdc.prime.router.azure.db.tables.pojos.ReportFile
import gov.cdc.prime.router.azure.db.tables.pojos.ReportLineage
import org.jooq.Configuration
import org.jooq.impl.DSL
import java.io.ByteArrayOutputStream

/**
 * This is a container class that holds information to be stored, about a single action,
 * as well as the reports that went into that Action, and were created by that Action.
 *
 * The idea is that, as an action progresses, call various track*(...) methods here to add additional information to
 * this container, in-memory only.
 *
 * Then when the action is done, call saveToDb(...) to plunk all the tracked information into the database.
 *
 */
class ActionHistory {

    private var context: ExecutionContext?

    /**
     * Throughout, using generated mutable jooq POJOs to store history information
     *
     */

    val action = Action()

    /*
     * Reports that are inputs to this action, from previous steps.
     * These reports are already in report_file.  For this action, we insert them as parents into
     * report_lineage.
     */
    val reportsIn = mutableMapOf<ReportId, ReportFile>()

    /*
     * Reports that are inputs to this action, from external source.
     * Note that this should be able to handle multiple submitted reports in one action.
     * For this action, we insert these into report_file, and as parents into report_lineage.
     */
    val reportsReceived = mutableMapOf<ReportId, ReportFile>()

    /*
     * New reports generated by this action.
     * For this action, we insert these into report_file, and as children into report_lineage.
     */
    val reportsOut = mutableMapOf<ReportId, ReportFile>()

    /**
     *
     * Collection of all the parent-child report relationships created by this action.
     *
     * Note:  There is a strong OO argument that this list should be broken out into each individual child Report.kt.
     * (That is, every report should know its own parents!)
     * However, its here because there are Functions that do not create Report.kt objects.  For example, Send.
     * In addition, in-memory, reports get copied many times, with lots of parent-child relationships
     * that are error-prone to track.  Hiding the lineage data here helps ensure correctness and hide complexity.
     *
     * todo Note that this does not work for command line.   Is that a problem?
     * todo this is redundant with `Report.sources`.   Merge these together; eliminate one of them.
     *
     */
    private val reportLineages = mutableListOf<ReportLineage>()

    constructor(taskAction: TaskAction, context: ExecutionContext? = null) {
        action.actionName = taskAction
        this.context = context
    }

    fun setActionType(taskAction: TaskAction) {
        action.actionName = taskAction
    }

    fun trackActionParams(request: HttpRequestMessage<String?>) {
        val factory = JsonFactory()
        val outStream = ByteArrayOutputStream()
        factory.createGenerator(outStream).use {
            it.useDefaultPrettyPrinter()
            it.writeStartObject()
            it.writeStringField("method", request.httpMethod.toString())
            it.writeObjectFieldStart("Headers")
            // remove secrets
            request.headers.filter { !it.key.contains("key") }.forEach { (key, value) ->
                it.writeStringField(key, value)
            }
            it.writeEndObject()
            it.writeObjectFieldStart("QueryParameters")
            // remove secrets
            request.queryParameters.filter { !it.key.contains("code") }.forEach { (key, value) ->
                it.writeStringField(key, value)
            }
            it.writeEndObject()
            it.writeEndObject()
        }
        trackActionParams(outStream.toString())
    }

    /**
     * Always appends, to allow for actions that do a mix of work (eg, SEND)
     */
    fun trackActionParams(actionParams: String) {
        val tmp = if (action.actionParams.isNullOrBlank()) actionParams else "${action.actionParams}, $actionParams"
        // kluge to get the max size of the varchar
        val max = ACTION.ACTION_PARAMS.dataType.length()
        // truncate if needed
        action.actionParams = tmp.chunked(size = max)[0]
    }

    /**
     * Always appends
     */
    fun trackActionResult(actionResult: String) {
        val tmp = if (action.actionResult.isNullOrBlank()) actionResult else "${action.actionResult}, $actionResult"
        val max = ACTION.ACTION_RESULT.dataType.length()
        action.actionResult = tmp.chunked(size = max)[0]
    }

    fun trackActionResult(httpResponseMessage: HttpResponseMessage) {
        trackActionResult(httpResponseMessage.status.toString() + ":\n" + httpResponseMessage.body.toString())
    }

    /**
     * Sanity check: No report can be tracked twice, either as an input or output.
     * Prevents at least tight loops, and other shenanigans.
     */
    private fun isReportAlreadyTracked(id: ReportId): Boolean {
        return reportsReceived.containsKey(id) ||
            reportsIn.containsKey(id) ||
            reportsOut.containsKey(id)
    }

    /**
     * track that this report is used in this Action.
     * Note: the report is already in the database.  Just need this for lineage purposes.
     */
    fun trackExistingInputReport(reportId: ReportId) {
        if (isReportAlreadyTracked(reportId)) {
            error("Bug:  attempt to track history of a report ($reportId) we've already associated with this action")
        }
        val reportFile = ReportFile()
        reportFile.reportId = reportId
        reportsIn[reportId] = reportFile
    }

    /**
     * Use this to record history info about a new externally submitted report.
     */
    fun trackExternalInputReport(incomingReport: ReportFunction.ValidatedRequest) {
        val report = incomingReport.report ?: error("No report to track!")
        if (isReportAlreadyTracked(report.id)) {
            error("Bug:  attempt to track history of a report ($report.id) we've already associated with this action")
        }

        val reportFile = ReportFile()
        reportFile.reportId = report.id
        reportFile.nextAction = TaskAction.none
        if (report.sources.size != 1) {
            error("An external incoming report should have only one source.   Report ${report.id} had ${report.sources.size} sources")
        }
        val source = (report.sources[0] as ClientSource)
        reportFile.sendingOrg = source.organization
        reportFile.sendingOrgClient = source.client
        reportFile.schemaName = report.schema.name
        reportFile.schemaTopic = report.schema.topic
        reportFile.bodyUrl = report.bodyURL
        reportFile.bodyFormat = report.bodyFormat.toString()
        reportFile.itemCount = report.itemCount
        reportsReceived[reportFile.reportId] = reportFile
    }

    /* Table structure here for reference during development. Might be out of date.
        public ReportFile(
            UUID           reportId,
            Integer        actionId,
            TaskAction     nextAction,
            OffsetDateTime nextActionAt,
            String         sendingOrg,
            String         sendingOrgClient,
            String         receivingOrg,
            String         receivingOrgSvc,
            String         schemaName,
            String         schemaTopic,
            String         bodyUrl,
            String         external_name,
            String         bodyFormat,
            byte[]         blobDigest,
            Integer        itemCount,
            OffsetDateTime wipedAt,
            OffsetDateTime createdAt
        */
    /**
     * Use this to record history info about an internally created report.
     */
    fun trackCreatedReport(
        event: Event,
        report: Report,
        service: OrganizationService
    ) {
        if (isReportAlreadyTracked(report.id)) {
            error("Bug:  attempt to track history of a report ($report.id) we've already associated with this action")
        }

        val reportFile = ReportFile()
        reportFile.reportId = report.id
        reportFile.nextAction = event.eventAction.toTaskAction()
        reportFile.nextActionAt = event.at
        reportFile.receivingOrg = service.organization.name
        reportFile.receivingOrgSvc = service.name
        reportFile.schemaName = report.schema.name
        reportFile.schemaTopic = report.schema.topic
        reportFile.bodyUrl = report.bodyURL
        reportFile.bodyFormat = report.bodyFormat.toString()
        reportFile.itemCount = report.itemCount
        reportsOut[reportFile.reportId] = reportFile
    }

    fun trackSentReport(
        service: OrganizationService,
        sentReportId: ReportId,
        fileName: String?,
        params: String,
        result: String,
        itemCount: Int
    ) {
        if (isReportAlreadyTracked(sentReportId)) {
            error("Bug:  attempt to track history of a report ($sentReportId) we've already associated with this action")
        }
        val reportFile = ReportFile()
        reportFile.reportId = sentReportId
        reportFile.receivingOrg = service.organization.name
        reportFile.receivingOrgSvc = service.name
        reportFile.schemaName = service.schema
        reportFile.schemaTopic = service.topic
        reportFile.externalName = fileName
        reportFile.transportParams = params
        reportFile.transportResult = result
        reportFile.bodyUrl = null
        reportFile.bodyFormat = service.format.toString()
        reportFile.itemCount = itemCount
        reportsOut[reportFile.reportId] = reportFile
    }

    @Deprecated("Not sure we really need this")
    fun trackFailedReport(service: OrganizationService, sentReportId: ReportId, params: String, msg: String) {
        trackSentReport(service, sentReportId, null, params, msg, 0)
    }

    /**
     * Save the history about this action and related reports
     */
    fun saveToDb(txn: Configuration) {
        insertAll(txn)
    }

    private fun insertAll(txn: Configuration) {
        action.actionId = insertAction(txn)
        reportsReceived.values.forEach { it.actionId = action.actionId }
        reportsOut.values.forEach { it.actionId = action.actionId }
        insertReports(txn)
        generateReportLineages(action.actionId)
        insertLineages(txn)
    }

    /**
     * Returns the action_id PK of the newly inserted ACTION.
     */
    private fun insertAction(txn: Configuration): Long {
        val actionRecord = DSL.using(txn).newRecord(ACTION, action)
        actionRecord.store()
        val actionId = actionRecord.actionId
        context?.logger?.info("Saved to ACTION: ${action.actionName}, id=$actionId")
        return actionId
    }

    private fun insertReports(txn: Configuration) {
        reportsReceived.values.forEach {
            insertReportFile(it, txn)
        }
        reportsOut.values.forEach {
            insertReportFile(it, txn)
        }
    }

    private fun insertReportFile(reportFile: ReportFile, txn: Configuration) {
        DSL.using(txn).newRecord(REPORT_FILE, reportFile).store()
        val fromInfo =
            if (!reportFile.sendingOrg.isNullOrEmpty()) "${reportFile.sendingOrg}.${reportFile.sendingOrgClient} --> " else ""
        val toInfo =
            if (!reportFile.receivingOrg.isNullOrEmpty()) " --> ${reportFile.receivingOrg}.${reportFile.receivingOrgSvc}" else ""
        context?.logger?.info("Saved to REPORT_FILE: ${reportFile.reportId} (${fromInfo}action ${action.actionName}$toInfo)")
    }

    /**
     * Automatically generate parent/child relationships based on what's in the reports* collections.
     * For now, assume that every parent report played a hand in creating every child report.
     * This is a lovely simplification, because it means that the functions don't have to
     * worry about lineage tracking at all.
     */
    private fun generateReportLineages(actionId: Long) {
        reportsIn.keys.forEach { parentId ->
            reportsOut.keys.forEach { childId ->
                reportLineages.add(ReportLineage(null, actionId, parentId, childId, null))
            }
        }
        reportsReceived.keys.forEach { parentId ->
            reportsOut.keys.forEach { childId ->
                reportLineages.add(ReportLineage(null, actionId, parentId, childId, null))
            }
        }
    }

    private fun insertLineages(txn: Configuration) {
        reportLineages.forEach {
            insertReportLineage(it, txn)
        }
    }

    private fun insertReportLineage(lineage: ReportLineage, txn: Configuration) {
        DSL.using(txn).newRecord(REPORT_LINEAGE, lineage).store()
        context?.logger?.info("Report ${lineage.parentReportId} is a parent of child report ${lineage.childReportId}")
    }
}