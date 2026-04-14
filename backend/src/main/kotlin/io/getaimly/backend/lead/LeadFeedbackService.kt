package io.getaimly.backend.lead

import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class LeadNotificationPayload(
    val leadId:         Long,
    val chatTitle:      String,
    val text:           String,
    val link:           String,
    val keyword:        String,
    val authorUsername: String,
    val authorName:     String,
    val source:         LeadSource,
)

data class FeedbackExample(
    val rating:         LeadRating,
    val messageSnippet: String,
    val matchedKeyword: String,
)

@Service
class LeadFeedbackService(
    private val feedbackRepo: LeadFeedbackRepository,
    private val pendingRepo:  PendingLeadNotificationRepository,
    private val leadRepo:     LeadRepository,
    private val userRepo:     UserRepository,
    @Lazy private val bot:    AimlyBot,
) {
    private val log = LoggerFactory.getLogger(LeadFeedbackService::class.java)

    private val QUEUE_LIMIT              = 50
    private val MIN_FEEDBACKS_FOR_PROMPT = 3

    /**
     * Максимальное число примеров для AI-промпта.
     * Ограничено 10, чтобы не перегружать контекст токенами.
     * Приоритет: оценки по тому же ключевому слову (до 4) → общие последние (до 6).
     */
    private val MAX_PROMPT_EXAMPLES      = 10
    private val MAX_BY_KEYWORD           = 4
    private val MAX_GENERAL              = MAX_PROMPT_EXAMPLES - MAX_BY_KEYWORD  // 6

    // ─── Основная точка входа ─────────────────────────────────────────────────

    /**
     * Вызывается после AI-решения (или сразу, если AI выключен).
     * Либо отправляет уведомление немедленно, либо ставит в очередь + шлёт nudge.
     */
    @Transactional
    fun deliverOrEnqueue(user: User, payload: LeadNotificationPayload) {
        val tgId = user.telegramId ?: return

        val pendingLeadId = findUnratedNotifiedLeadId(user.id)

        if (pendingLeadId == null) {
            // Нет неоцененного — отправляем сразу
            sendAndMarkNotified(user, tgId, payload)
        } else {
            // Есть неоцененный — кладём в очередь
            enqueue(user, payload)

            val queueSize = pendingRepo.countByUserId(user.id)
            runCatching {
                bot.notifyLeadPending(
                    telegramChatId = tgId,
                    pendingLeadId  = pendingLeadId,
                    queueSize      = queueSize,
                )
            }.onFailure {
                log.warn("[FEEDBACK] Ошибка nudge: userId=${user.id} ${it.message}")
            }

            log.info(
                "[FEEDBACK] Лид #${payload.leadId} в очереди: userId=${user.id} " +
                        "неоцененный=#$pendingLeadId queueSize=$queueSize"
            )
        }
    }

    // ─── Сохранение оценки ───────────────────────────────────────────────────

    /**
     * Сохраняет оценку и доставляет следующий лид из очереди.
     * Вызывается из bot-callback и REST API.
     * Поддерживает upsert — повторная оценка меняет предыдущую.
     */
    @Transactional
    fun submitFeedback(user: User, leadId: Long, rating: LeadRating): LeadFeedback {
        val lead = leadRepo.findById(leadId).orElseThrow {
            NoSuchElementException("лид #$leadId не найден")
        }
        if (lead.user.id != user.id) throw SecurityException("нет доступа")

        // Upsert: удаляем старую оценку если есть (rating — val, не изменяется)
        val isChange = feedbackRepo.findByUserIdAndLeadId(user.id, leadId)?.also {
            feedbackRepo.delete(it)
            feedbackRepo.flush()
        } != null

        val feedback = feedbackRepo.save(
            LeadFeedback(
                user           = user,
                lead           = lead,
                rating         = rating,
                messageSnippet = lead.messageText.take(200),
                matchedKeyword = lead.matchedKeyword,
            )
        )

        log.info(
            "[FEEDBACK] Оценка${if (isChange) " изменена" else ""}: " +
                    "userId=${user.id} leadId=#$leadId rating=$rating keyword=\"${lead.matchedKeyword}\""
        )

        // Доставить следующий из очереди (только если это первичная оценка)
        if (!isChange) {
            deliverNextFromQueue(user)
        }

        return feedback
    }

    // ─── Примеры для AI-промпта ──────────────────────────────────────────────

    /**
     * Формирует список примеров оценок для вставки в AI-промпт.
     *
     * Логика выборки (не более MAX_PROMPT_EXAMPLES = 10 итого):
     *   1. До MAX_BY_KEYWORD (4) оценок по тому же ключевому слову — самые релевантные.
     *   2. До MAX_GENERAL (6) последних общих оценок — для контекста.
     *
     * Возвращает пустой список если накопленных оценок меньше MIN_FEEDBACKS_FOR_PROMPT (3) —
     * до этого порога данных недостаточно для значимой персонализации.
     */
    fun getFeedbackExamplesForPrompt(userId: Long, keyword: String): List<FeedbackExample> {
        if (feedbackRepo.countByUserId(userId) < MIN_FEEDBACKS_FOR_PROMPT) return emptyList()

        // Сначала — оценки по тому же ключевому слову (наиболее релевантны для промпта)
        val byKeyword = feedbackRepo.findRecentByUserIdAndKeyword(
            userId   = userId,
            keyword  = keyword,
            pageable = PageRequest.of(0, MAX_BY_KEYWORD),
        )

        val byKeywordIds = byKeyword.map { it.id }.toSet()
        val remaining    = MAX_GENERAL - (byKeyword.size - MAX_BY_KEYWORD).coerceAtLeast(0)

        // Дополняем общими последними оценками, исключая уже добавленные
        val general = if (remaining > 0) {
            feedbackRepo
                .findRecentByUserId(userId, PageRequest.of(0, remaining + byKeyword.size))
                .filter { it.id !in byKeywordIds }
                .take(remaining)
        } else {
            emptyList()
        }

        return (byKeyword + general)
            .take(MAX_PROMPT_EXAMPLES)
            .map {
                FeedbackExample(
                    rating         = it.rating,
                    messageSnippet = it.messageSnippet.take(150),
                    matchedKeyword = it.matchedKeyword,
                )
            }
    }

    // ─── Приватные методы ────────────────────────────────────────────────────

    /**
     * Ищет последний лид, который уже был отправлен пользователю (tgNotifiedAt != null),
     * но ещё не получил оценку. Запрос выполняется целиком в БД через NOT EXISTS.
     */
    private fun findUnratedNotifiedLeadId(userId: Long): Long? =
        leadRepo.findLatestNotifiedWithoutFeedback(
            userId   = userId,
            pageable = PageRequest.of(0, 1),
        ).firstOrNull()

    /**
     * Отправляет лид в Telegram и помечает tgNotifiedAt только при успехе.
     * Если Telegram-вызов упал — tgNotifiedAt не проставляется,
     * чтобы лид не застрял в «неоцененных» вечно.
     */
    private fun sendAndMarkNotified(user: User, tgId: Long, payload: LeadNotificationPayload) {
        runCatching {
            bot.notifyNewLead(
                telegramChatId = tgId,
                leadId         = payload.leadId,
                chatTitle      = payload.chatTitle,
                text           = payload.text,
                link           = payload.link,
                keyword        = payload.keyword,
                authorUsername = payload.authorUsername,
                authorName     = payload.authorName,
                source         = payload.source,
            )
        }.onSuccess {
            leadRepo.findById(payload.leadId).ifPresent { lead ->
                lead.tgNotifiedAt = LocalDateTime.now()
                leadRepo.save(lead)
            }
        }.onFailure {
            log.warn(
                "[FEEDBACK] Ошибка отправки: userId=${user.id} " +
                        "leadId=#${payload.leadId} ${it.message}"
            )
        }
    }

    private fun enqueue(user: User, payload: LeadNotificationPayload) {
        if (pendingRepo.existsByUserIdAndLeadId(user.id, payload.leadId)) return

        // Защита от переполнения: удаляем самый старый если достигнут лимит
        if (pendingRepo.countByUserId(user.id) >= QUEUE_LIMIT) {
            pendingRepo.findOldestByUserId(user.id, PageRequest.of(0, 1)).firstOrNull()?.let {
                pendingRepo.delete(it)
                log.warn(
                    "[FEEDBACK] Очередь переполнена, удалён старый: " +
                            "userId=${user.id} leadId=#${it.lead.id}"
                )
            }
        }

        val lead = leadRepo.findById(payload.leadId).orElse(null) ?: return
        pendingRepo.save(
            PendingLeadNotification(
                user           = user,
                lead           = lead,
                chatTitle      = payload.chatTitle,
                messagePreview = payload.text.take(300),
                messageLink    = payload.link,
                keyword        = payload.keyword,
                authorUsername = payload.authorUsername,
                authorName     = payload.authorName,
            )
        )
    }

    private fun deliverNextFromQueue(user: User) {
        val tgId = user.telegramId ?: return

        val next = pendingRepo
            .findOldestByUserId(user.id, PageRequest.of(0, 1))
            .firstOrNull() ?: return

        pendingRepo.delete(next)
        pendingRepo.flush()

        sendAndMarkNotified(
            user    = user,
            tgId    = tgId,
            payload = LeadNotificationPayload(
                leadId         = next.lead.id,
                chatTitle      = next.chatTitle,
                text           = next.messagePreview,
                link           = next.messageLink,
                keyword        = next.keyword,
                authorUsername = next.authorUsername,
                authorName     = next.authorName,
                source         = LeadSource.LIVE,
            )
        )

        log.info("[FEEDBACK] Из очереди доставлен: userId=${user.id} leadId=#${next.lead.id}")
    }
}