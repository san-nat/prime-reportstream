// ktlint-disable filename
package gov.cdc.prime.router.cli.tests

import com.github.ajalt.clikt.output.TermUi
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.azure.HttpUtilities
import gov.cdc.prime.router.azure.ReportStreamEnv
import gov.cdc.prime.router.cli.FileUtilities
import java.net.HttpURLConnection
import java.io.File
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import gov.cdc.prime.router.ReportId
import gov.cdc.prime.router.azure.WorkflowEngine
import com.google.common.base.CharMatcher


/**
 * Generates a fake HL7 report and sends it to ReportStream, waits some time then checks the lineage to make
 * sure the data was sent to the configured senders.
 */
class Hl7Ingest : CoolTest() {
    override val name = "hl7ingest"
    override val description = "Create HL7 Fake data, submit, wait, confirm sent via database lineage data"
    override val status = TestStatus.SMOKE

    override fun run(environment: ReportStreamEnv, options: CoolTestOptions): Boolean {
        var passed = true
        val sender = hl7Sender
        val receivers = allGoodReceivers
        val itemCount = options.items * receivers.size
        ugly("Starting $name Test: send ${sender.fullName} data to $allGoodCounties")
        val file = FileUtilities.createFakeFile(
            metadata,
            sender,
            itemCount,
            receivingStates,
            allGoodCounties,
            options.dir,
            Report.Format.HL7_BATCH
        )
        TermUi.echo("Created datafile $file")

        // Now send it to ReportStream.
        val (responseCode, json) =
            HttpUtilities.postReportFile(environment, file, sender, options.key)
        if (responseCode != HttpURLConnection.HTTP_CREATED) {
            bad("***$name Test FAILED***:  response code $responseCode")
            passed = false
        } else {
            good("Posting of report succeeded with response code $responseCode")
        }

        // Check the response from the endpoint
        TermUi.echo(json)
        passed = passed and examineResponse(json)

        // Now check the lineage data
        waitABit(25, environment)
        val reportId = getReportIdFromResponse(json)
        if (reportId != null) {
            passed = passed and examineLineageResults(reportId, receivers, itemCount)
        }

        return passed
    }
}

class BadHl7 : CoolTest() {
    override val name = "badhl7"
    override val description = "Submit badly formatted hl7 files - should get errors"
    override val status = TestStatus.SMOKE
    override fun run(environment: ReportStreamEnv, options: CoolTestOptions): Boolean {
        val listBadCharacters = listOf(
            "ÅÍÎÏ˝ÓÔ\uF8FFÒÚÆ☃",
            "OBR|1|A241Z^MESA_ORDPLC||P2^Procedure 2^ERL_MESA|||||||||xxx||Radiology^^^^R|7101^ESTRADA^JAIME^P^^DR|||||||||||1^once^^20000701^^R|||WALK|Modality Test 241||||||||||A||\n",
            "PID|||||Richards^Mary||19340428|F|||||||||||||||||||\nPID|||||||19700510105000|M|||||||||||||||||||\n", /* multiple patient information*/
            "~!@#\\\$^&*()-_=+[]\\\\{}|;':,./<>?",
            "❤️ \uD83D\uDC94 \uD83D\uDC8C \uD83D\uDC95 \uD83D\uDC9E \uD83D\uDC93",
            "<a href=\"javascript\\x0A:javascript:alert(1)\" id=\"fuzzelement1\">test</a>",
            "'; EXEC sp_MSForEachTable 'DROP TABLE ?'; --",
            "1'000'000,00"
        )
        var passed = false
        val sender = hl7Sender
        listBadCharacters.forEachIndexed { i, badCharacters ->
            ugly("Starting badcsv file Test $i: submitting with $badCharacters")
            val reFile = FileUtilities.replaceText(
                "./src/test/hl7_test_files/invalid_characters_template.hl7",
                "replaceMe",
                "$badCharacters"
            )

            if (!reFile.exists()) {
                error("Unable to find file ${reFile.absolutePath} to do badhl7 test")
            }

            val (responseCode, json) = HttpUtilities.postReportFile(
                environment,
                reFile,
                sender,
                options.key
            )
            TermUi.echo("Response to POST: $responseCode")
            TermUi.echo("Response to POST: $json")

            if (responseCode >= 400) {
                good("Test of Bad HL7 file $badCharacters passed: Failure HttpStatus code was returned.")
                passed = true
            } else {
                bad("***badhl7 Test $i of $badCharacters FAILED: Expecting a failure HttpStatus. ***")
            }
//            try {
//                val tree = jacksonObjectMapper().readTree(json)
//                if (tree["id"] == null || tree["id"].isNull) {
//                    good("Test of Bad CSV file $filename passed: No UUID was returned.")
//                } else {
//                    bad("***badcsv Test $i of $filename FAILED: RS returned a valid UUID for a bad CSV. ***")
//                    passed = false
//                }
//                if (tree["errorCount"].intValue() > 0) {
//                    good("Test of Bad CSV file $filename passed: At least one error was returned.")
//                } else {
//                    bad("***badcsv Test $i of $filename FAILED: No error***")
//                    passed = false
//                }
//            } catch (e: Exception) {
//                passed = bad("***badcsv Test $i of $filename FAILED***: Unexpected json returned")
//            }
        }
        return passed
    }
}


class Hl7Check : CoolTest() {
    override val name = "hl7check"
    override val description = "Submit badly formatted hl7 files - should get errors"
    override val status = TestStatus.SMOKE
    override fun run(environment: ReportStreamEnv, options: CoolTestOptions): Boolean {
        val listBadCharacters = listOf(
            ""
        )
        var passed = false
        val sender = hl7Sender
        listBadCharacters.forEachIndexed { i, badCharacters ->
            ugly("Starting badcsv file Test $i: submitting with $badCharacters")
            val reFile = FileUtilities.replaceText(
                "./src/test/hl7_test_files/invalid_characters_template.hl7",
                "replaceMe",
                "$badCharacters"
            )

            if (!reFile.exists()) {
                error("Unable to find file ${reFile.absolutePath} to do badhl7 test")
            }

            val (responseCode, json) = HttpUtilities.postReportFile(
                environment,
                reFile,
                sender,
                options.key
            )
            TermUi.echo("Response to POST: $responseCode")
            TermUi.echo("Response to POST: $json")

//            waitABit(10, environment)
            Thread.sleep(10_000)
            db = WorkflowEngine().db
            var asciiOnly = false
//            val receiverName = hl7Receiver.name
            val receiverName = "fl-phd"
            val reportId = getReportIdFromResponse(json)
                ?: return bad("***$name Test FAILED***: A report ID came back as null")

            TermUi.echo("Id of submitted report: $reportId")
            db.transact { txn ->
                val filename = sftpFilenameQuery(txn, reportId, receiverName)
                // If we get a file, test the contents to see if it is all ASCII only.
                TermUi.echo(filename)
//                TermUi.echo(File(filename).readText())
                if (filename != null) {
                    val contents = File(options.sftpDir, filename).inputStream().readBytes().toString(Charsets.UTF_8)
                    asciiOnly = CharMatcher.ascii().matchesAllOf(contents)
                }
            }
            if (asciiOnly) {
                return bad("***intcontent Test FAILED***: File contents are only ASCII characters")
            } else {
                return good("Test passed: for intcontent")
            }

//            if (responseCode >= 400) {
//                good("Test of Bad HL7 file $badCharacters passed: Failure HttpStatus code was returned.")
//                passed = true
//            } else {
//                bad("***badhl7 Test $i of $badCharacters FAILED: Expecting a failure HttpStatus. ***")
//            }
//            try {
//                val tree = jacksonObjectMapper().readTree(json)
//                if (tree["id"] == null || tree["id"].isNull) {
//                    good("Test of Bad CSV file $filename passed: No UUID was returned.")
//                } else {
//                    bad("***badcsv Test $i of $filename FAILED: RS returned a valid UUID for a bad CSV. ***")
//                    passed = false
//                }
//                if (tree["errorCount"].intValue() > 0) {
//                    good("Test of Bad CSV file $filename passed: At least one error was returned.")
//                } else {
//                    bad("***badcsv Test $i of $filename FAILED: No error***")
//                    passed = false
//                }
//            } catch (e: Exception) {
//                passed = bad("***badcsv Test $i of $filename FAILED***: Unexpected json returned")
//            }
        }
        return passed
    }
}