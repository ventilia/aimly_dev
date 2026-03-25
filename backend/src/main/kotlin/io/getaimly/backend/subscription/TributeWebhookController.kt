package io.getaimly.backend.subscription

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.referral.ReferralService
import io.getaimly.backend.user.UserRepository
import jakarta.persistence.*
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64


@JsonIgnoreProperties(ignoreUnknown = true)
data class TributeWebhookRequest(
    val name: String = "",
    @JsonProperty("event_id")   val eventId:   String? = null,
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

@Entity
@Table(
    name = "tribute_webhook_events",
    indexes = [Index(name = "idx_tribute_event_key", columnList = "event_key", unique = true)]
)
class TributeWebhookEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "event_key", nullable = false, unique = true, length = 512)
    val eventKey: String,

    @Column(name = "event_name", nullable = false, length = 64)
    val eventName: String,

    @Column(name = "telegram_user_id")
    val telegramUserId: Long?,

    @Column(name = "processed_at", nullable = false, updatable = false)
    val processedAt: LocalDateTime = LocalDateTime.now(),
)

@Repository
interface TributeWebhookEventRepository : JpaRepository<TributeWebhookEvent, Long> {
    fun existsByEventKey(eventKey: String): Boolean
}


@RestController
@RequestMapping("/api/v1/webhooks/tribute")
class TributeWebhookController(
    private val userRepository:   UserRepository,
    private val expiryRepository: SubscriptionExpiryRepository,
    private val eventRepository:  TributeWebhookEventRepository,
    private val objectMapper:     ObjectMapper,
    private val bot:              AimlyBot,
    private val cfg:              TributeProperties,
    private val referralService:  ReferralService,   // ← новый
) {
    private val log = LoggerFactory.getLogger(TributeWebhookController::class.java)

    companion object {
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val TRIBUTE_PLAN   = "START"
    }


    @GetMapping
    fun ping(): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok(mapOf("status" to "ok", "endpoint" to "tribute-webhook"))


    @PostMapping(consumes = ["application/json", "application/*+json", "*/*"])
    fun handleWebhook(
        httpRequest: HttpServletRequest,
        @RequestHeader(value = "trbt-signature", required = false) signature: String?,
    ): ResponseEntity<Map<String, String>> {

        val contentLength = httpRequest.contentLength
        if (contentLength > MAX_BODY_BYTES) {
            log.warn("Tribute webhook: тело запроса слишком большое ($contentLength байт), отклоняем")
            return ResponseEntity.status(413).body(mapOf("status" to "error", "reason" to "payload too large"))
        }

        val rawBytes = httpRequest.inputStream.use { it.readBytes(MAX_BODY_BYTES) }
        if (rawBytes.size >= MAX_BODY_BYTES) {
            log.warn("Tribute webhook: тело запроса превысило $MAX_BODY_BYTES байт, отклоняем")
            return ResponseEntity.status(413).body(mapOf("status" to "error", "reason" to "payload too large"))
        }

        val rawBody = String(rawBytes, Charsets.UTF_8)

        val signatureValid = verifySignature(rawBytes, signature)
        if (!signatureValid) {
            if (cfg.signatureStrict) {
                log.warn("Tribute webhook: неверная или отсутствующая подпись — запрос отклонён (strict mode)")
                return ResponseEntity.ok(mapOf("status" to "ignored", "reason" to "invalid_signature"))
            }
            log.warn("Tribute webhook: подпись не прошла проверку, но strict=false — продолжаем")
        }

        if (rawBody.isBlank()) {
            log.warn("Tribute webhook: пустое тело запроса")
            return ResponseEntity.ok(mapOf("status" to "ok"))
        }

        val event = runCatching {
            objectMapper.readValue(rawBody, TributeWebhookRequest::class.java)
        }.getOrElse { e ->
            log.error("Tribute webhook: не удалось распарсить JSON: ${e.message}")
            return ResponseEntity.ok(mapOf("status" to "ok"))
        }

        val eventTime = parseEventTime(event.sentAt ?: event.createdAt)
        if (eventTime != null) {
            val ageMinutes = java.time.Duration.between(eventTime, LocalDateTime.now(ZoneOffset.UTC)).toMinutes()
            if (ageMinutes > cfg.maxEventAgeMinutes) {
                log.warn(
                    "Tribute webhook: событие '${event.name}' слишком старое " +
                            "(возраст=${ageMinutes}мин, max=${cfg.maxEventAgeMinutes}мин) — пропускаем"
                )
                return ResponseEntity.ok(mapOf("status" to "ok", "reason" to "event_too_old"))
            }
            if (ageMinutes < -2) {
                log.warn("Tribute webhook: событие из будущего (age=${ageMinutes}мин) — пропускаем")
                return ResponseEntity.ok(mapOf("status" to "ok", "reason" to "event_from_future"))
            }
        } else {
            log.warn("Tribute webhook: нет метки времени в событии '${event.name}' — пропускаем проверку возраста")
        }

        val eventKey = buildEventKey(event)
        if (eventRepository.existsByEventKey(eventKey)) {
            log.info("Tribute webhook: событие '${event.name}' key='$eventKey' уже обработано — пропускаем")
            return ResponseEntity.ok(mapOf("status" to "ok", "reason" to "already_processed"))
        }

        log.info(
            "Tribute webhook: обрабатываем event='${event.name}' " +
                    "tgId=${event.payload?.telegramUserId} key='$eventKey'"
        )

        runCatching {
            when (event.name) {
                "new_subscription"       -> handleNewSubscription(event)
                "renewed_subscription"   -> handleRenewedSubscription(event)
                "cancelled_subscription" -> handleCancelledSubscription(event)
                else -> {
                    log.debug("Tribute webhook: игнорируем неизвестное событие '${event.name}'")
                    return ResponseEntity.ok(mapOf("status" to "ok"))
                }
            }
        }.onFailure { e ->
            log.error("Tribute webhook: ошибка обработки '${event.name}': ${e.message}", e)
            return ResponseEntity.ok(mapOf("status" to "ok"))
        }

        runCatching {
            eventRepository.save(
                TributeWebhookEvent(
                    eventKey       = eventKey,
                    eventName      = event.name,
                    telegramUserId = event.payload?.telegramUserId,
                )
            )
        }.onFailure { e ->
            if (e is DataIntegrityViolationException) {
                log.info("Tribute webhook: гонка при сохранении идемпотентности для key='$eventKey' — ок")
            } else {
                log.warn("Tribute webhook: не удалось сохранить idempotency record: ${e.message}")
            }
        }

        return ResponseEntity.ok(mapOf("status" to "ok"))
    }


    @Transactional
    fun handleNewSubscription(req: TributeWebhookRequest) {
        val payload   = req.payload ?: run { log.warn("new_subscription: нет payload"); return }
        val tgId      = payload.telegramUserId ?: run { log.warn("new_subscription: нет telegram_user_id"); return }
        val plan      = resolvePlan(payload.subscriptionName)
        val expiresAt = resolveExpiresAt(payload.expiresAt, defaultDays = 31)

        val user = userRepository.findByTelegramId(tgId).orElse(null) ?: run {
            log.warn("[SUB][WARN] Оплата без привязки TG: tgId=$tgId — пользователь не найден. Убедитесь что пользователь привязал Telegram перед оформлением подписки.")
            return
        }

        user.subscriptionStatus = "ACTIVE"
        user.subscriptionPlan   = plan
        user.updatedAt          = LocalDateTime.now()
        userRepository.save(user)

        // Сохраняем expiresAt от Tribute. Буфер bonusDaysBuffer не трогаем — он
        // мог уже содержать бонусные дни от предыдущих рефералов.
        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)

        log.info("[SUB] Оплачена (Tribute new): userId=${user.id} email=${user.email} plan=$plan до=$expiresAt tgId=$tgId")

        // ── Реферальный бонус ─────────────────────────────────────────────────
        // Если этот пользователь пришёл по чьей-то реферальной ссылке —
        // начисляем реферреру +5 дней в bonusDaysBuffer. Идемпотентно.
        runCatching {
            val bonusGranted = referralService.grantBonusIfEligible(user)
            if (bonusGranted) {
                log.info("[REFERRAL] Бонус реферреру начислен за нового подписчика userId=${user.id}")
            }
        }.onFailure { e ->
            log.warn("[REFERRAL] Ошибка начисления бонуса реферреру для userId=${user.id}: ${e.message}")
        }
        // ─────────────────────────────────────────────────────────────────────

        runCatching {
            bot.sendText(
                tgId,
                "🎉 Подписка AIMLY активирована!\n\n" +
                        "✅ Тариф ${planDisplayName(plan)} — все функции включены\n" +
                        "✅ AI-поиск и фильтрация лидов\n" +
                        "✅ Мониторинг без ограничений\n\n" +
                        "📅 Действует до: ${expiresAt.toLocalDate()}\n\n" +
                        "Спасибо! 🙌"
            )
        }.onFailure { log.warn("new_subscription TG-уведомление userId=${user.id}: ${it.message}") }
    }

    @Transactional
    fun handleRenewedSubscription(req: TributeWebhookRequest) {
        val payload   = req.payload ?: run { log.warn("renewed_subscription: нет payload"); return }
        val tgId      = payload.telegramUserId ?: run { log.warn("renewed_subscription: нет telegram_user_id"); return }
        val plan      = resolvePlan(payload.subscriptionName)
        val expiresAt = resolveExpiresAt(payload.expiresAt, defaultDays = 31)

        val user = userRepository.findByTelegramId(tgId).orElse(null) ?: run {
            log.warn("[SUB][WARN] Продление без привязки TG: tgId=$tgId — пользователь не найден")
            return
        }

        if (user.subscriptionStatus != "ACTIVE" || user.subscriptionPlan != plan) {
            user.subscriptionStatus = "ACTIVE"
            user.subscriptionPlan   = plan
        }
        user.updatedAt = LocalDateTime.now()
        userRepository.save(user)

        // Обновляем только expiresAt — bonusDaysBuffer не трогаем!
        // Буфер останется нетронутым и будет использован только если
        // следующий автоплатёж не пройдёт.
        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)

        log.info("[SUB] Продлена (Tribute): userId=${user.id} email=${user.email} plan=$plan до=$expiresAt")

        runCatching {
            bot.sendText(
                tgId,
                "🔄 Подписка AIMLY продлена!\n\n" +
                        "📅 Действует до: ${expiresAt.toLocalDate()}\n\n" +
                        "Спасибо! 🙌"
            )
        }.onFailure { log.warn("renewed_subscription TG-уведомление userId=${user.id}: ${it.message}") }
    }

    @Transactional
    fun handleCancelledSubscription(req: TributeWebhookRequest) {
        val payload = req.payload ?: run { log.warn("cancelled_subscription: нет payload"); return }
        val tgId    = payload.telegramUserId ?: run { log.warn("cancelled_subscription: нет telegram_user_id"); return }

        val user = userRepository.findByTelegramId(tgId).orElse(null) ?: run {
            log.warn("[SUB][WARN] Отмена без привязки TG: tgId=$tgId — пользователь не найден")
            return
        }

        user.updatedAt = LocalDateTime.now()
        userRepository.save(user)

        val expiresAt = expiryRepository.findByUserId(user.id)?.expiresAt
        log.info("[SUB] Отменена (Tribute): userId=${user.id} email=${user.email} доступ до=$expiresAt cancelReason=${payload.cancelReason}")

        runCatching {
            val tillLine = if (expiresAt != null) "\nДоступ сохраняется до: ${expiresAt.toLocalDate()}" else ""
            bot.sendText(
                tgId,
                "ℹ️ Подписка AIMLY отменена.\n\n" +
                        "Автопродление отключено.$tillLine\n\n" +
                        "Для возобновления — подпишитесь снова через Tribute."
            )
        }.onFailure { log.warn("cancelled_subscription TG-уведомление userId=${user.id}: ${it.message}") }
    }


    // ─── Вспомогательные ─────────────────────────────────────────────────────────

    private fun verifySignature(rawBytes: ByteArray, signature: String?): Boolean {
        if (cfg.apiKey.isBlank()) {
            log.warn("Tribute webhook: tribute.api-key не задан — проверка подписи невозможна")
            return false
        }
        if (signature.isNullOrBlank()) {
            log.warn("Tribute webhook: заголовок trbt-signature отсутствует")
            return false
        }

        val cleanSig = signature.trim()
            .removePrefix("sha256=")
            .removePrefix("HMAC-SHA256=")

        val hmacBytes   = computeHmacBytes(rawBytes, cfg.apiKey)
        val expectedHex = hmacBytes.toHexString()
        val expectedB64 = Base64.getEncoder().encodeToString(hmacBytes)

        val matchHex = MessageDigest.isEqual(
            cleanSig.lowercase().toByteArray(Charsets.UTF_8),
            expectedHex.toByteArray(Charsets.UTF_8),
        )
        val matchB64 = MessageDigest.isEqual(
            cleanSig.toByteArray(Charsets.UTF_8),
            expectedB64.toByteArray(Charsets.UTF_8),
        )

        return (matchHex || matchB64).also { valid ->
            if (!valid) log.warn("Tribute webhook: подпись не совпала")
            else        log.debug("Tribute webhook: подпись верна ✓")
        }
    }

    private fun buildEventKey(event: TributeWebhookRequest): String {
        val base = event.eventId?.takeIf { it.isNotBlank() }
            ?: "${event.name}|${event.payload?.telegramUserId}|${event.payload?.subscriptionId}|${event.createdAt}"
        return base.take(512)
    }

    private fun computeHmacBytes(body: ByteArray, key: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    private fun parseEventTime(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            OffsetDateTime.parse(raw).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        }.getOrElse { e ->
            log.warn("Tribute: не удалось распарсить время='$raw': ${e.message}")
            null
        }
    }

    private fun resolveExpiresAt(raw: String?, defaultDays: Long): LocalDateTime {
        val parsed = parseEventTime(raw)
        if (parsed == null) {
            log.warn("Tribute: expires_at отсутствует или неверный формат '$raw', ставим +${defaultDays}д от now(UTC)")
        }
        return parsed ?: LocalDateTime.now(ZoneOffset.UTC).plusDays(defaultDays)
    }

    private fun resolvePlan(@Suppress("UNUSED_PARAMETER") subscriptionName: String?): String = TRIBUTE_PLAN

    private fun planDisplayName(plan: String) = when (plan) {
        "MINIMUM" -> "МИНИМУМ"
        "START"   -> "СТАРТ"
        else      -> plan
    }

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val buf   = ByteArray(limit + 1)
        var total = 0
        while (total <= limit) {
            val read = read(buf, total, limit + 1 - total)
            if (read == -1) break
            total += read
        }
        return buf.copyOf(total)
    }
}