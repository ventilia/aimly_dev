package io.getaimly.backend.email

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class EmailService(
    @Value("\${resend.api-key}") private val apiKey: String,
    @Value("\${resend.from-email:noreply@getaimly.io}") private val fromEmail: String,
    @Value("\${resend.from-name:AIMLY}") private val fromName: String,
) {
    private val log = LoggerFactory.getLogger(EmailService::class.java)

    private val client = RestClient.builder()
        .baseUrl("https://api.resend.com")
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build()

    fun sendVerificationCode(to: String, code: String, firstName: String?) {
        val name = firstName ?: "там"
        send(
            to      = to,
            subject = "Подтверждение почты — AIMLY",
            html    = buildVerificationEmail(name, code),
        )
    }

    fun sendPasswordResetCode(to: String, code: String, firstName: String?) {
        val name = firstName ?: "там"
        send(
            to      = to,
            subject = "Сброс пароля — AIMLY",
            html    = buildPasswordResetEmail(name, code),
        )
    }

    private fun send(to: String, subject: String, html: String) {
        val body = mapOf(
            "from"    to "$fromName <$fromEmail>",
            "to"      to listOf(to),
            "subject" to subject,
            "html"    to html,
        )

        try {
            client.post()
                .uri("/emails")
                .body(body)
                .retrieve()
                .toBodilessEntity()

            log.info("письмо отправлено на $to")
        } catch (e: Exception) {
            log.error("ошибка отправки письма на $to: ${e.message}", e)
            throw RuntimeException("Не удалось отправить письмо")
        }
    }

    private fun buildVerificationEmail(name: String, code: String) = """
        <!DOCTYPE html>
        <html>
        <body style="font-family: Arial, sans-serif; background:#f8f7f4; padding:40px;">
          <div style="max-width:480px; margin:0 auto; background:#fff;
                      border-radius:16px; padding:40px; border:1px solid #e4e2dc;">
            <h2 style="color:#0f0e0c; margin-bottom:8px;">Привет, $name</h2>
            <p style="color:#3d3b36; margin-bottom:24px;">
              Введи этот код чтобы подтвердить почту в AIMLY:
            </p>
            <div style="background:#eeebfb; border-radius:12px; padding:24px;
                        text-align:center; margin-bottom:24px;">
              <span style="font-size:36px; font-weight:800; letter-spacing:8px;
                           color:#5c39df;">$code</span>
            </div>
            <p style="color:#7a786f; font-size:14px;">
              Код действителен 15 минут. Если ты не регистрировался в AIMLY — просто проигнорируй это письмо.
            </p>
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun buildPasswordResetEmail(name: String, code: String) = """
        <!DOCTYPE html>
        <html>
        <body style="font-family: Arial, sans-serif; background:#f8f7f4; padding:40px;">
          <div style="max-width:480px; margin:0 auto; background:#fff;
                      border-radius:16px; padding:40px; border:1px solid #e4e2dc;">
            <h2 style="color:#0f0e0c; margin-bottom:8px;">Привет, $name</h2>
            <p style="color:#3d3b36; margin-bottom:8px;">
              Мы получили запрос на сброс пароля для вашего аккаунта AIMLY.
            </p>
            <p style="color:#3d3b36; margin-bottom:24px;">
              Введите этот код для подтверждения:
            </p>
            <div style="background:#eeebfb; border-radius:12px; padding:24px;
                        text-align:center; margin-bottom:24px;">
              <span style="font-size:36px; font-weight:800; letter-spacing:8px;
                           color:#5c39df;">$code</span>
            </div>
            <p style="color:#7a786f; font-size:14px; margin-bottom:8px;">
              Код действителен 15 минут.
            </p>
            <p style="color:#7a786f; font-size:14px;">
              Если вы не запрашивали сброс пароля — просто проигнорируйте это письмо. Ваш пароль останется прежним.
            </p>
          </div>
        </body>
        </html>
    """.trimIndent()
}