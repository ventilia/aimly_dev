package io.getaimly.backend.lead

import io.getaimly.backend.user.User
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class LeadFeedbackRequest(
    val rating: String,  // "GOOD" or "BAD"
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
) {
    private val log = LoggerFactory.getLogger(LeadFeedbackController::class.java)

    /**
     * POST /api/v1/leads/{leadId}/feedback
     *
     * Body: { "rating": "GOOD" }  или  { "rating": "BAD" }
     *
     * После успешной оценки:
     *   - рейтинг сохраняется в поле leads.user_rating
     *   - nudge-сообщение в Telegram автоматически удаляется (если было отправлено)
     *   - следующий лид из очереди доставляется в Telegram
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

        feedbackService.submitFeedback(user, leadId, rating)
        val queueEmpty = pendingRepo.countByUserId(user.id) == 0L

        log.info(
            "[FEEDBACK][WEB] userId=${user.id} leadId=#$leadId rating=$rating queueEmpty=$queueEmpty"
        )

        return ResponseEntity.ok(
            LeadFeedbackResponse(
                leadId     = leadId,
                rating     = rating.name,
                queueEmpty = queueEmpty,
            )
        )
    }

    /**
     * GET /api/v1/leads/feedback-status
     *
     * Возвращает размер очереди ожидающих уведомлений.
     * Фронтенд использует это при загрузке страницы лидов,
     * чтобы определить — нужно ли показать "оцените предыдущий" блок.
     */
    @GetMapping("/feedback-status")
    fun getFeedbackStatus(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<Map<String, Any?>> {
        val queueSize = pendingRepo.countByUserId(user.id)
        return ResponseEntity.ok(
            mapOf(
                "queueSize" to queueSize,
                "hasQueue"  to (queueSize > 0),
            )
        )
    }
}