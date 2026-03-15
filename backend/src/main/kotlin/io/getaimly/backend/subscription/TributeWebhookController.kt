package io.getaimly.backend.subscription

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.user.UserRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.time.OffsetDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ─── DTOs ─────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class TributeWebhookRequest(
    val name: String = "",
    @JsonProperty("created_at") val createdAt: String? = null,
    @JsonProperty("sent_at")    val sentAt:    String? = null,
    val payload: TributePayload? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TributePayload(
    @JsonProperty("subscription_id")   val subscriptionId:   Long?   = null,
    @JsonProperty("subscription_name") val subscriptionName: String? = null,
    val period:                               String? = null,
    val price:                                Long?   = null,
    val currency:                             String? = null,
    val type:                                 String? = null,
    @JsonProperty("expires_at")        val expiresAt:        String? = null,
    @JsonProperty("trb_user_id")        val trbUserId:        String? = null,
    @JsonProperty("telegram_user_id")   val telegramUserId:   Long?   = null,
    @JsonProperty("telegram_username")  val telegramUsername: String? = null,
    @JsonProperty("channel_id")   val channelId:   Long?   = null,
    @JsonProperty("channel_name") val channelName: String? = null,
    @JsonProperty("cancel_reason") val cancelReason: String? = null,
)

// ─── Controller ───────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/webhooks/tribute")
class TributeWebhookController(
    private val userRepository:      UserRepository,
    private val expiryRepository:    SubscriptionExpiryRepository,
    private val objectMapper:        ObjectMapper,
    private val bot:                 AimlyBot,
    @Value("\${tribute.api-key:}") private val tributeApiKey: String,
) {
    private val log = LoggerFactory.getLogger(TributeWebhookController::class.java)

    @PostMapping(consumes = ["application/json", "application/*", "*/*"])
    fun handleWebhook(
        request: HttpServletRequest,
        @RequestHeader(value = "trbt-signature", required = false) signature: String?,
    ): ResponseEntity<Map<String, String>> {

        // ── Читаем тело как байты — работает с любым Content-Type ────────────
        val rawBytes = request.inputStream.readBytes()
        val rawBody  = String(rawBytes, Charsets.UTF_8)

        log.info("Tribute webhook: signature=$signature body=$rawBody")

        // ── Проверка HMAC-SHA256 подписи ──────────────────────────────────────
        if (tributeApiKey.isNotBlank()) {
            if (signature.isNullOrBlank()) {
                log.warn("Tribute webhook: отсутствует заголовок trbt-signature")
                return ResponseEntity.status(401).body(mapOf("error" to "missing signature"))
            }
            val expected = computeHmac(rawBytes, tributeApiKey)
            if (!expected.equals(signature.trim(), ignoreCase = true)) {
                log.warn("Tribute webhook: неверная подпись. got='$signature' expected='$expected'")
                return ResponseEntity.status(401).body(mapOf("error" to "invalid signature"))
            }
        } else {
            log.warn("Tribute webhook: tribute.api-key не задан — проверка подписи пропущена!")
        }

        // ── Парсинг JSON ──────────────────────────────────────────────────────
        val webhookRequest = runCatching {
            objectMapper.readValue(rawBody, TributeWebhookRequest::class.java)
        }.getOrElse { e ->
            log.error("Tribute webhook: не удалось распарсить JSON: ${e.message}. Body: $rawBody")
            return ResponseEntity.badRequest().body(mapOf("error" to "invalid json"))
        }

        log.info("Tribute webhook: event=${webhookRequest.name} tg=${webhookRequest.payload?.telegramUserId}")

        // ── Диспетчер событий ─────────────────────────────────────────────────
        when (webhookRequest.name) {
            "new_subscription"       -> handleNewSubscription(webhookRequest)
            "renewed_subscription"   -> handleRenewedSubscription(webhookRequest)
            "cancelled_subscription" -> handleCancelledSubscription(webhookRequest)
            else -> log.debug("Tribute webhook: игнорируем событие '${webhookRequest.name}'")
        }

        return ResponseEntity.ok(mapOf("status" to "ok"))
    }

    // ─── Обработчики событий ──────────────────────────────────────────────────

    private fun handleNewSubscription(req: TributeWebhookRequest) {
        val payload   = req.payload ?: run { log.warn("new_subscription: нет payload"); return }
        val tgId      = payload.telegramUserId ?: run { log.warn("new_subscription: нет telegram_user_id"); return }
        val expiresAt = parseExpiresAt(payload.expiresAt) ?: LocalDateTime.now().plusDays(31)

        val user = userRepository.findByTelegramId(tgId).orElse(null)
            ?: run { log.warn("new_subscription: пользователь telegramId=$tgId не найден"); return }

        user.subscriptionStatus = "ACTIVE"
        user.subscriptionPlan   = "MINIMUM"
        userRepository.save(user)

        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)

        log.info("Tribute new_subscription: userId=${user.id} email=${user.email} до $expiresAt")

        runCatching {
            bot.sendText(
                tgId,
                "🎉 Подписка AIMLY активирована!\n\n" +
                        "✅ Тариф МИНИМУМ — все функции включены\n" +
                        "✅ AI-поиск и фильтрация лидов\n" +
                        "✅ Мониторинг без ограничений\n\n" +
                        "📅 Действует до: ${expiresAt.toLocalDate()}\n\n" +
                        "Спасибо! 🙌"
            )
        }.onFailure { log.warn("new_subscription TG-уведомление: ${it.message}") }
    }

    private fun handleRenewedSubscription(req: TributeWebhookRequest) {
        val payload   = req.payload ?: run { log.warn("renewed_subscription: нет payload"); return }
        val tgId      = payload.telegramUserId ?: run { log.warn("renewed_subscription: нет telegram_user_id"); return }
        val expiresAt = parseExpiresAt(payload.expiresAt) ?: LocalDateTime.now().plusDays(31)

        val user = userRepository.findByTelegramId(tgId).orElse(null)
            ?: run { log.warn("renewed_subscription: пользователь telegramId=$tgId не найден"); return }

        if (user.subscriptionStatus != "ACTIVE") {
            user.subscriptionStatus = "ACTIVE"
            user.subscriptionPlan   = "MINIMUM"
        }
        userRepository.save(user)

        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)

        log.info("Tribute renewed_subscription: userId=${user.id} email=${user.email} до $expiresAt")

        runCatching {
            bot.sendText(
                tgId,
                "🔄 Подписка AIMLY продлена!\n\n" +
                        "📅 Действует до: ${expiresAt.toLocalDate()}\n\n" +
                        "Спасибо! 🙌"
            )
        }.onFailure { log.warn("renewed_subscription TG-уведомление: ${it.message}") }
    }

    private fun handleCancelledSubscription(req: TributeWebhookRequest) {
        val payload = req.payload ?: run { log.warn("cancelled_subscription: нет payload"); return }
        val tgId    = payload.telegramUserId ?: run { log.warn("cancelled_subscription: нет telegram_user_id"); return }

        val user = userRepository.findByTelegramId(tgId).orElse(null)
            ?: run { log.warn("cancelled_subscription: пользователь telegramId=$tgId не найден"); return }

        val expiresAt = expiryRepository.findByUserId(user.id)?.expiresAt
        log.info("Tribute cancelled_subscription: userId=${user.id} email=${user.email} expiresAt=$expiresAt")

        runCatching {
            val tillLine = if (expiresAt != null) "\nДоступ сохраняется до: ${expiresAt.toLocalDate()}" else ""
            bot.sendText(
                tgId,
                "ℹ️ Подписка AIMLY отменена.\n\n" +
                        "Автопродление отключено.$tillLine\n\n" +
                        "После окончания срока мониторинг остановится.\n" +
                        "Для возобновления — подпишитесь снова через Tribute."
            )
        }.onFailure { log.warn("cancelled_subscription TG-уведомление: ${it.message}") }
    }

    // ─── Утилиты ──────────────────────────────────────────────────────────────

    private fun computeHmac(body: ByteArray, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun parseExpiresAt(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            OffsetDateTime.parse(raw).toLocalDateTime()
        }.getOrElse { e ->
            log.warn("Tribute: не удалось распарсить expires_at='$raw': ${e.message}")
            null
        }
    }
}