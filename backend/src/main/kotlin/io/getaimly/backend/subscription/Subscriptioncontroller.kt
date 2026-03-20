package io.getaimly.backend.subscription

import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.user.Role
import io.getaimly.backend.user.User
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/subscriptions")
class SubscriptionController(private val service: SubscriptionService) {
    private val log = LoggerFactory.getLogger(SubscriptionController::class.java)

    private fun requireAdmin(user: User) {
        if (user.role != Role.ADMIN) throw ForbiddenException("только для администраторов")
    }

    @GetMapping
    fun list(@AuthenticationPrincipal user: User): ResponseEntity<List<SubscriptionDto>> {
        requireAdmin(user)
        return ResponseEntity.ok(service.getAll())
    }

    @PostMapping("/grant")
    fun grant(
        @AuthenticationPrincipal user: User,
        @RequestBody req: GrantSubscriptionRequest,
    ): ResponseEntity<SubscriptionDto> {
        requireAdmin(user)
        return ResponseEntity.ok(service.grant(req))
    }

    @PostMapping("/set-expiry")
    fun setExpiry(
        @AuthenticationPrincipal user: User,
        @RequestBody req: SetExpiryRequest,
    ): ResponseEntity<SubscriptionDto> {
        requireAdmin(user)
        return ResponseEntity.ok(service.setExpiry(req))
    }

    @PatchMapping("/{userId}/plan")
    fun changePlan(
        @AuthenticationPrincipal user: User,
        @PathVariable userId: Long,
        @RequestBody body: ChangePlanBody,
    ): ResponseEntity<SubscriptionDto> {
        requireAdmin(user)
        return ResponseEntity.ok(
            service.changePlan(
                ChangePlanRequest(
                    userId = userId,
                    plan   = body.plan,
                    status = body.status ?: "ACTIVE",
                )
            )
        )
    }

    @PostMapping("/{userId}/revoke")
    fun revoke(
        @AuthenticationPrincipal user: User,
        @PathVariable userId: Long,
    ): ResponseEntity<SubscriptionDto> {
        requireAdmin(user)
        return ResponseEntity.ok(service.revoke(userId))
    }



    @Scheduled(cron = "0 0 9 * * *")
    fun checkExpiringSubscriptions() {
        log.info("запуск проверки истекающих подписок")
        service.notifyExpiring()
        service.deactivateExpired()
    }
}

data class ChangePlanBody(
    val plan:   String,
    val status: String? = null,
)