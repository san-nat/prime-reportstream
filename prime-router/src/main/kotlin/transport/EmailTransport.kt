package gov.cdc.prime.router.transport

import com.sendgrid.Method
import com.sendgrid.Request
import com.sendgrid.SendGrid
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects.Content
import com.sendgrid.helpers.mail.objects.Email
import com.sendgrid.helpers.mail.objects.Personalization
import gov.cdc.prime.router.EmailTransportType
import gov.cdc.prime.router.Receiver
import gov.cdc.prime.router.ReportId
import gov.cdc.prime.router.azure.ActionHistory
import gov.cdc.prime.router.azure.WorkflowEngine
import org.apache.logging.log4j.kotlin.Logging
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templateresolver.StringTemplateResolver
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Calendar

class EmailTransport : ITransport, Logging {
    override fun startSession(receiver: Receiver): Closeable? {
        return null
    }

    override fun send(
        header: WorkflowEngine.Header,
        sentReportId: ReportId,
        retryItems: RetryItems?,
        session: Any?,
        actionHistory: ActionHistory,
    ): RetryItems? {

        val emailTransport = header.receiver?.transport as EmailTransportType
        val content = buildContent(header)
        val mail = buildMail(content, emailTransport)

        try {
            val sg = SendGrid(System.getenv("SENDGRID_API_KEY"))
            val request = Request()
            request.setMethod(Method.POST)
            request.setEndpoint("mail/send")
            request.setBody(mail.build())
            sg.api(request)
        } catch (ex: Exception) {
            logger.error("Email/SendGrid exception", ex)
            return RetryToken.allItems
        }
        return null
    }

    fun getTemplateEngine(): TemplateEngine {
        val templateEngine = TemplateEngine()
        val stringTemplateResolver = StringTemplateResolver()
        templateEngine.setTemplateResolver(stringTemplateResolver)
        return templateEngine
    }

    fun getTemplateFromAttributes(htmlContent: String, attr: Map<String, Any>): String {
        val templateEngine = getTemplateEngine()
        val context = Context()
        attr.forEach { (k, v) -> context.setVariable(k, v) }
        return templateEngine.process(htmlContent, context)
    }

    fun buildContent(header: WorkflowEngine.Header): Content {
        val htmlTemplate = Files.readString(Path.of("./assets/email-templates/test-results-ready__inline.html"))

        val attr = mapOf(
            "today" to Calendar.getInstance(),
            "file" to header.reportFile.reportId
        )

        val html = getTemplateFromAttributes(htmlTemplate, attr)
        val content = Content("text/html", html)
        return content
    }

    fun buildMail(content: Content, emailTransport: EmailTransportType): Mail {
        val subject = "COVID-19 Reporting:  Your test results are ready"

        val mail = Mail()
        mail.setFrom(Email(emailTransport.from))
        mail.setSubject(subject)
        mail.addContent(content)
        mail.setReplyTo(Email("noreply@cdc.gov"))
        val personalization = Personalization()
        emailTransport.addresses.forEach {
            personalization.addTo(Email(it))
        }
        mail.addPersonalization(personalization)
        return mail
    }
}