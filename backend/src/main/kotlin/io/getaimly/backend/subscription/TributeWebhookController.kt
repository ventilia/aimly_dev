package io.getaimly.backend.subscription

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.time.OffsetDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ─── Webhook payload DTOs ─────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class TributeWebhookRequest(
    val name: String,
    @JsonProperty("created_at") val createdAt: String? = null,
    @JsonProperty("sent_at")    val sentAt:    String? = null,
    val payload: TributePayload? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TributePayload(
    // Подписка
    @JsonProperty("subscription_id")   val subscriptionId:   Long?    = null,
    @JsonProperty("subscription_name") val subscriptionName: String?  = null,
    val period:                               String?  = null,
    val price:                                Long?    = null,
    val currency:                             String?  = null,
    val type:                                 String?  = null,   // regular | gift | trial
    @JsonProperty("expires_at")              val expiresAt: String? = null,

    // Пользователь
    @JsonProperty("trb_user_id")        val trbUserId:        String?  = null,
    @JsonProperty("telegram_user_id")   val telegramUserId:   Long?    = null,
    @JsonProperty("telegram_username")  val telegramUsername: String?  = null,

    // Канал
    @JsonProperty("channel_id")   val channelId:   Long?   = null,
    @JsonProperty("channel_name") val channelName: String? = null,
    @JsonProperty("cancel_reason") val cancelReason: String? = null,
)

// ─── Controller ───────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/webhooks/tribute")
class TributeWebhookController(
    private val userRepository:   UserRepository,
    private val expiryRepository: SubscriptionExpiryRepository,
    private val subscriptionService: SubscriptionService,
    private val bot: AimlyBot,
    private val objectMapper: ObjectMapper,
    @Value("\${tribute.api-key:}") private val tributeApiKey: String,
) {
    private val log = LoggerFactory.getLogger(TributeWebhookController::class.java)

    /**
     * POST /api/v1/webhooks/tribute
     *
     * Принимает вебхуки от Tribute.
     * Документация: https://wiki.tribute.tg/ru/api-dokumentaciya/vebkhuki
     *
     * Заголовок trbt-signature: HMAC-SHA256(requestBody, apiKey)
     *
     * Обрабатываемые события:
     *  - new_subscription      → активируем подписку MINIMUM
     *  - renewed_subscription  → продлеваем подписку
     *  - cancelled_subscription → помечаем как отменённую (подписка живёт до expires_at)
     */
    @PostMapping
    fun handleWebhook(
        @RequestHeader(value = "trbt-signature", required = false) signature: String?,
        @RequestBody rawBody: ByteArray,
    ): ResponseEntity<Map<String, String>> {

        // ── Проверка подписи ──────────────────────────────────────────────────
        if (tributeApiKey.isNotBlank()) {
            if (signature.isNullOrBlank()) {
                log.warn("Tribute webhook: отсутствует заголовок trbt-signature")
                return ResponseEntity.status(401).body(mapOf("error" to "missing signature"))
            }
            val expected = computeHmac(rawBody, tributeApiKey)
            if (!expected.equals(signature, ignoreCase = true)) {
                log.warn("Tribute webhook: неверная подпись. got='$signature' expected='$expected'")
                return ResponseEntity.status(401).body(mapOf("error" to "invalid signature"))
            }
        } else {
            log.warn("Tribute webhook: tribute.api-key не задан — проверка подписи пропущена (только для разработки!)")
        }

        // ── Парсинг ───────────────────────────────────────────────────────────
        val request = runCatching {
            objectMapper.readValue(rawBody, TributeWebhookRequest::class.java)
        }.getOrElse { e ->
            log.error("Tribute webhook: не удалось распарсить тело: ${e.message}")
            return ResponseEntity.badRequest().body(mapOf("error" to "invalid json"))
        }

        log.info("Tribute webhook получен: name=${request.name} payload=${request.payload?.let { "tg=${it.telegramUserId} expires=${it.expiresAt}" }}")

        // ── Диспетчер событий ─────────────────────────────────────────────────
        when (request.name) {
            "new_subscription"      -> handleNewSubscription(request)
            "renewed_subscription"  -> handleRenewedSubscription(request)
            "cancelled_subscription" -> handleCancelledSubscription(request)
            else -> log.debug("Tribute webhook: игнорируем событие '${request.name}'")
        }

        return ResponseEntity.ok(mapOf("status" to "ok"))
    }

    // ─── Обработчики событий ─────────────────────────────────────────────────

    /**
     * Новая подписка: активируем тариф MINIMUM до expires_at.
     * Ищем пользователя по telegram_user_id (самый надёжный идентификатор).
     */
    private fun handleNewSubscription(req: TributeWebhookRequest) {
        val payload = req.payload ?: run { log.warn("new_subscription: нет payload"); return }
        val tgId    = payload.telegramUserId ?: run { log.warn("new_subscription: нет telegram_user_id"); return }
        val expiresAt = parseExpiresAt(payload.expiresAt) ?: LocalDateTime.now().plusDays(31)

        val user = userRepository.findByTelegramId(tgId).orElse(null)
            ?: run {
                log.warn("new_subscription: пользователь с telegramId=$tgId не найден в системе")
                return
            }

        // Активируем подписку MINIMUM
        user.subscriptionStatus = "ACTIVE"
        user.subscriptionPlan   = "MINIMUM"
        userRepository.save(user)

        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)

        log.info("Tribute new_subscription: userId=${user.id} email=${user.email} до $expiresAt")

        // Telegram-уведомление
        runCatching {
            bot.sendText(
                tgId,
                "🎉 *Подписка AIMLY активирована!*\n\n" +
                        "✅ Тариф МИНИМУМ — все функции включены\n" +
                        "✅ AI-поиск и фильтрация лидов\n" +
                        "✅ Мониторинг без ограничений\n\n" +
                        "📅 Действует до: ${expiresAt.toLocalDate()}\n\n" +
                        "Спасибо за поддержку! 🙌"
            )
        }.onFailure { log.warn("new_subscription: не удалось отправить TG-уведомление: ${it.message}") }
    }

    /**
     * Продление: обновляем expires_at, статус уже должен быть ACTIVE.
     */
    private fun handleRenewedSubscription(req: TributeWebhookRequest) {
        val payload = req.payload ?: run { log.warn("renewed_subscription: нет payload"); return }
        val tgId    = payload.telegramUserId ?: run { log.warn("renewed_subscription: нет telegram_user_id"); return }
        val expiresAt = parseExpiresAt(payload.expiresAt) ?: LocalDateTime.now().plusDays(31)

        val user = userRepository.findByTelegramId(tgId).orElse(null)
            ?: run { log.warn("renewed_subscription: пользователь с telegramId=$tgId не найден"); return }

        // Убеждаемся что статус ACTIVE (мог истечь между платежами)
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
                "🔄 *Подписка AIMLY продлена!*\n\n" +
                        "📅 Действует до: ${expiresAt.toLocalDate()}\n\n" +
                        "Спасибо за доверие! 🙌"
            )
        }.onFailure { log.warn("renewed_subscription: не удалось отправить TG-уведомление: ${it.message}") }
    }

    /**
     * Отмена: НЕ деактивируем сразу — пусть подписка доживёт до expires_at.
     * Просто логируем и уведомляем пользователя.
     * Реальная деактивация произойдёт в SubscriptionService.deactivateExpired().
     */
    private fun handleCancelledSubscription(req: TributeWebhookRequest) {
        val payload = req.payload ?: run { log.warn("cancelled_subscription: нет payload"); return }
        val tgId    = payload.telegramUserId ?: run { log.warn("cancelled_subscription: нет telegram_user_id"); return }

        val user = userRepository.findByTelegramId(tgId).orElse(null)
            ?: run { log.warn("cancelled_subscription: пользователь с telegramId=$tgId не найден"); return }

        val expiresAt = expiryRepository.findByUserId(user.id)?.expiresAt

        log.info("Tribute cancelled_subscription: userId=${user.id} email=${user.email} expiresAt=$expiresAt")

        // Уведомляем пользователя: подписка не будет продлена, но действует до конца периода
        runCatching {
            val tillLine = if (expiresAt != null) "\nДоступ сохраняется до: ${expiresAt.toLocalDate()}" else ""
            bot.sendText(
                tgId,
                "ℹ️ *Подписка AIMLY отменена*\n\n" +
                        "Автоматическое продление отключено.$tillLine\n\n" +
                        "После окончания срока мониторинг остановится.\n" +
                        "Для возобновления — подпишитесь снова через Tribute."
            )
        }.onFailure { log.warn("cancelled_subscription: не удалось отправить TG-уведомление: ${it.message}") }
    }

    // ─── Утилиты ─────────────────────────────────────────────────────────────

    /**
     * Вычисляет HMAC-SHA256(body, key) и возвращает hex-строку.
     */
    private fun computeHmac(body: ByteArray, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /**
     * Парсит строку вида "2025-04-20T01:15:57.305733Z" в LocalDateTime.
     * Возвращает null если строка пустая или невалидная.
     */
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