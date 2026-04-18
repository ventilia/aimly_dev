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
    private val MAX_PROMPT_EXAMPLES      = 20
    private val MAX_BY_KEYWORD           = 8
    private val MAX_GENERAL              = MAX_PROMPT_EXAMPLES - MAX_BY_KEYWORD  // 12

    // ─── Основная точка входа ─────────────────────────────────────────────────

    /**
     * Вызывается после AI-решения (или сразу, если AI выключен).
     * Либо отправляет уведомление немедленно, либо ставит в очередь + шлёт nudge.
     *
     * Nudge — это сообщение в TG вида «оцените предыдущий, ещё N лидов в очереди».
     * ID этого сообщения сохраняется ОДНОВРЕМЕННО:
     *   - в pending_lead_notifications.nudge_tg_message_id — для быстрого поиска
     *   - в leads.nudge_tg_message_id — резервно, чтобы удалить даже если pending уже удалён
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
                val nudgeMsgId = bot.notifyLeadPending(
                    telegramChatId = tgId,
                    pendingLeadId  = pendingLeadId,
                    queueSize      = queueSize,
                    messagePreview = msgPreview,
                    matchedKeyword = matchedKw,
                )

                // Сохраняем ID nudge-сообщения в двух местах для надёжности
                if (nudgeMsgId != null) {
                    // 1. В pending-записи (для быстрого поиска при удалении)
                    pendingRepo.findByUserIdAndLeadId(user.id, pendingLeadId)?.let { pending ->
                        pending.nudgeTgMessageId = nudgeMsgId
                        pendingRepo.save(pending)
                    }

                    // 2. Непосредственно на самом лиде — резервная копия.
                    // Это позволяет удалить nudge даже если pending уже удалён
                    // (например при параллельном запросе оценки из бота и с фронта).
                    leadRepo.findById(pendingLeadId).ifPresent { lead ->
                        lead.nudgeTgMessageId = nudgeMsgId
                        leadRepo.save(lead)
                        log.debug(
                            "[FEEDBACK] nudgeTgMessageId=$nudgeMsgId сохранён: " +
                                    "userId=${user.id} pendingLeadId=#$pendingLeadId"
                        )
                    }
                }
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
     * При первичной оценке автоматически переводит NEW → VIEWED.
     * После сохранения:
     *   1. Удаляет nudge-сообщение из Telegram.
     *      Ищет ID сначала в pending-записи, затем резервно — в поле leads.nudge_tg_message_id.
     *   2. Доставляет следующий лид из очереди (только при первичной оценке).
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

        // Очищаем nudge_tg_message_id на лиде после того как запомнили его для удаления
        val nudgeMsgIdFromLead = lead.nudgeTgMessageId
        lead.nudgeTgMessageId  = null

        leadRepo.save(lead)

        log.info(
            "[FEEDBACK] Оценка${if (isChange) " изменена" else ""}: " +
                    "userId=${user.id} leadId=#$leadId rating=$rating keyword=\"${lead.matchedKeyword}\""
        )

        // Удаляем nudge-сообщение из Telegram.
        // Приоритет: pending-запись → резервное поле на лиде.
        deleteNudgeForLead(user, leadId, nudgeMsgIdFromLead)

        // Доставить следующий из очереди только при первичной оценке
        if (!isChange) {
            deliverNextFromQueue(user)
        }

        return lead
    }

    // ─── Примеры для AI-промпта ──────────────────────────────────────────────

    /**
     * Формирует список примеров оценок для вставки в AI-промпт.
     * Читает оценённые лиды прямо из таблицы leads (поля userRating + ratingAt).
     *
     * Логика выборки (не более MAX_PROMPT_EXAMPLES = 20 итого):
     *   1. До MAX_BY_KEYWORD (8) по тому же ключевому слову — самые релевантные.
     *   2. До MAX_GENERAL (12) последних общих оценок — для контекста.
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
                    messageSnippet = it.messageText.take(150),
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
     * Удаляет nudge-сообщение из Telegram.
     *
     * Порядок поиска ID сообщения:
     *   1. pending_lead_notifications.nudge_tg_message_id — основное место хранения.
     *   2. nudgeMsgIdFromLead — резервное значение из поля leads.nudge_tg_message_id,
     *      которое передаётся явно из submitFeedback до удаления pending-записи.
     *
     * Такой двойной поиск защищает от race condition: если оценка пришла одновременно
     * из бота и с фронта — pending-запись к этому моменту может уже не существовать,
     * но значение из поля лида мы уже прочитали до сохранения.
     */
    private fun deleteNudgeForLead(user: User, leadId: Long, nudgeMsgIdFromLead: Int?) {
        val tgId = user.telegramId ?: return

        // Пробуем найти ID сначала в pending-записи
        val msgId = pendingRepo.findByUserIdAndLeadId(user.id, leadId)?.nudgeTgMessageId
            ?: nudgeMsgIdFromLead  // резерв — прочитали из лида до очистки

        if (msgId == null) {
            log.debug("[FEEDBACK] Nudge не найден (не отправлялся?): userId=${user.id} leadId=#$leadId")
            return
        }

        runCatching {
            bot.deleteMessage(tgId, msgId)
            log.info("[FEEDBACK] Nudge удалён: userId=${user.id} leadId=#$leadId msgId=$msgId")
        }.onFailure {
            // Сообщение могло быть уже удалено пользователем — не критично
            log.debug(
                "[FEEDBACK] Не удалось удалить nudge (возможно уже удалено): " +
                        "userId=${user.id} leadId=#$leadId msgId=$msgId — ${it.message}"
            )
        }
    }

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