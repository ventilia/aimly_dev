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
    private val pendingRepo: PendingLeadNotificationRepository,
    private val leadRepo:    LeadRepository,
    private val userRepo:    UserRepository,
    @Lazy private val bot:   AimlyBot,
) {
    private val log = LoggerFactory.getLogger(LeadFeedbackService::class.java)

    private val QUEUE_LIMIT              = 50
    private val MIN_FEEDBACKS_FOR_PROMPT = 3
    private val MAX_PROMPT_EXAMPLES      = 25
    private val MAX_BY_KEYWORD           = 10
    private val MAX_GENERAL              = MAX_PROMPT_EXAMPLES - MAX_BY_KEYWORD  // 15

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
            // Нет неоценённого — отправляем сразу
            sendAndMarkNotified(user, tgId, payload)
        } else {
            // Есть неоценённый — кладём в очередь
            enqueue(user, payload)

            val queueSize   = pendingRepo.countByUserId(user.id)
            val pendingLead = leadRepo.findById(pendingLeadId).orElse(null)
            val msgPreview  = pendingLead?.messageText?.take(150) ?: ""
            val matchedKw   = pendingLead?.matchedKeyword ?: ""

            runCatching {
                bot.notifyLeadPending(
                    telegramChatId = tgId,
                    pendingLeadId  = pendingLeadId,
                    queueSize      = queueSize,
                    messagePreview = msgPreview,
                    matchedKeyword = matchedKw,
                )
            }.onFailure {
                log.warn("[FEEDBACK] Ошибка nudge: userId=${user.id} ${it.message}")
            }

            log.info(
                "[FEEDBACK] Лид #${payload.leadId} в очереди: userId=${user.id} " +
                        "неоценённый=#$pendingLeadId queueSize=$queueSize"
            )
        }
    }

    // ─── Сохранение оценки ───────────────────────────────────────────────────

    /**
     * Сохраняет оценку прямо в полях Lead (userRating + ratingAt).
     * Поддерживает upsert — повторная оценка перезаписывает предыдущую.
     *
     * При первичной оценке:
     *   - NEW → VIEWED автоматически
     *   - pending-запись удаляется
     *   - следующий лид из очереди доставляется в Telegram
     */
    @Transactional
    fun submitFeedback(user: User, leadId: Long, rating: LeadRating): Lead {
        val lead = leadRepo.findById(leadId).orElseThrow {
            NoSuchElementException("лид #$leadId не найден")
        }
        if (lead.user.id != user.id) throw SecurityException("нет доступа")

        val isChange = lead.userRating != null

        lead.userRating = rating
        lead.ratingAt   = LocalDateTime.now()

        // При первичной оценке NEW → VIEWED
        if (!isChange && lead.status == LeadStatus.NEW) {
            lead.status = LeadStatus.VIEWED
            log.info("[FEEDBACK] Лид авто-помечен VIEWED: userId=${user.id} leadId=#$leadId")
        }

        leadRepo.save(lead)

        log.info(
            "[FEEDBACK] Оценка${if (isChange) " изменена" else ""}: " +
                    "userId=${user.id} leadId=#$leadId rating=$rating keyword=\"${lead.matchedKeyword}\""
        )

        // Удаляем pending-запись и доставляем следующий из очереди только при первичной оценке
        if (!isChange) {
            pendingRepo.deleteByUserIdAndLeadId(user.id, leadId)
            deliverNextFromQueue(user)
        }

        return lead
    }

    // ─── Примеры для AI-промпта ──────────────────────────────────────────────

    /**
     * Формирует список примеров оценок для вставки в AI-промпт.
     * Читает оценённые лиды прямо из таблицы leads (поля userRating + ratingAt).
     *
     * Логика выборки (не более MAX_PROMPT_EXAMPLES = 25 итого):
     *   1. До MAX_BY_KEYWORD (10) по тому же ключевому слову — самые релевантные.
     *   2. До MAX_GENERAL (15) последних общих оценок — для контекста.
     *
     * Возвращает пустой список если оценок меньше MIN_FEEDBACKS_FOR_PROMPT (3).
     */
    fun getFeedbackExamplesForPrompt(userId: Long, keyword: String): List<FeedbackExample> {
        if (leadRepo.countByUserIdAndUserRatingNotNull(userId) < MIN_FEEDBACKS_FOR_PROMPT) {
            return emptyList()
        }

        val byKeyword = leadRepo.findRatedByUserIdAndKeyword(
            userId   = userId,
            keyword  = keyword,
            pageable = PageRequest.of(0, MAX_BY_KEYWORD),
        )

        val byKeywordIds = byKeyword.map { it.id }.toSet()
        val remaining    = MAX_GENERAL - (byKeyword.size - MAX_BY_KEYWORD).coerceAtLeast(0)

        val general = if (remaining > 0) {
            leadRepo
                .findRecentRatedByUserId(userId, PageRequest.of(0, remaining + byKeyword.size))
                .filter { it.id !in byKeywordIds }
                .take(remaining)
        } else {
            emptyList()
        }

        return (byKeyword + general)
            .take(MAX_PROMPT_EXAMPLES)
            .map {
                FeedbackExample(
                    rating         = it.userRating!!,
                    messageSnippet = it.messageText.take(200),
                    matchedKeyword = it.matchedKeyword,
                )
            }
    }

    // ─── Приватные методы ────────────────────────────────────────────────────

    /**
     * Ищет последний лид, который уже был отправлен пользователю (tgNotifiedAt != null),
     * но ещё не получил оценку (userRating IS NULL).
     */
    private fun findUnratedNotifiedLeadId(userId: Long): Long? =
        leadRepo.findLatestNotifiedWithoutRating(
            userId   = userId,
            pageable = PageRequest.of(0, 1),
        ).firstOrNull()

    /**
     * Отправляет лид в Telegram и помечает tgNotifiedAt только при успехе.
     * Если Telegram-вызов упал — tgNotifiedAt не проставляется,
     * чтобы лид не застрял в «неоценённых» вечно.
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