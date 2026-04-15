package io.getaimly.backend.lead

import io.getaimly.backend.user.User
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class LeadFeedbackRequest(
    val rating: String,  // "GOOD" или "BAD"
)

data class LeadFeedbackResponse(
    val leadId:     Long,
    val rating:     String,
    // true — следующий лид из очереди уже отправлен в TG (либо очередь пуста)
    val queueEmpty: Boolean,
)

/**
 * REST-эндпоинт для оценки лидов с веб-сайта.
 * Тот же [LeadFeedbackService] что и у бота — оценки едины.
 */
@RestController
@RequestMapping("/api/v1/leads")
class LeadFeedbackController(
    private val feedbackService: LeadFeedbackService,
    private val pendingRepo:     PendingLeadNotificationRepository,
    private val leadRepo:        LeadRepository,
) {
    private val log = LoggerFactory.getLogger(LeadFeedbackController::class.java)

    /**
     * POST /api/v1/leads/{leadId}/feedback
     *
     * Body: { "rating": "GOOD" }  или  { "rating": "BAD" }
     */
    @PostMapping("/{leadId}/feedback")
    fun submitFeedback(
        @AuthenticationPrincipal user: User,
        @PathVariable leadId: Long,
        @RequestBody req: LeadFeedbackRequest,
    ): ResponseEntity<LeadFeedbackResponse> {

        val rating = runCatching { LeadRating.valueOf(req.rating.uppercase()) }
            .getOrElse {
                return ResponseEntity.badRequest().build()
            }

        val feedback   = feedbackService.submitFeedback(user, leadId, rating)
        val queueEmpty = pendingRepo.countByUserId(user.id) == 0L

        log.info(
            "[FEEDBACK][WEB] userId=${user.id} leadId=#$leadId rating=$rating queueEmpty=$queueEmpty"
        )

        return ResponseEntity.ok(
            LeadFeedbackResponse(
                leadId     = leadId,
                rating     = feedback.rating.name,
                queueEmpty = queueEmpty,
            )
        )
    }

    /**
     * GET /api/v1/leads/feedback-status
     *
     * Возвращает состояние очереди оценок:
     *   - queueSize     — количество лидов, ожидающих доставки в TG
     *   - hasQueue      — есть ли очередь
     *   - pendingLeadId — ID первого неоцененного уведомленного лида (null если все оценены).
     *                     Именно этот лид блокирует очередь и должен быть оценен первым.
     *
     * Фронтенд использует это:
     *   1) На странице лидов — для баннера очереди и подсветки неоцененного лида.
     *   2) На главной странице — чтобы показать именно тот лид, который ждёт оценки.
     */
    @GetMapping("/feedback-status")
    fun getFeedbackStatus(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<Map<String, Any?>> {
        val queueSize = pendingRepo.countByUserId(user.id)

        // Первый неоцененный уведомленный лид — тот, что нужно оценить прямо сейчас.
        // findLatestNotifiedWithoutFeedback возвращает ID лида с tgNotifiedAt != null
        // и без записи в lead_feedbacks для данного пользователя.
        val pendingLeadId = leadRepo
            .findLatestNotifiedWithoutFeedback(user.id, PageRequest.of(0, 1))
            .firstOrNull()

        return ResponseEntity.ok(
            mapOf(
                "queueSize"     to queueSize,
                "hasQueue"      to (queueSize > 0),
                "pendingLeadId" to pendingLeadId,
            )
        )
    }
}