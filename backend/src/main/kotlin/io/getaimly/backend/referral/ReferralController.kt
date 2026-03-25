package io.getaimly.backend.referral

import io.getaimly.backend.user.User
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/referral")
class ReferralController(
    private val referralService: ReferralService,
) {

    /**
     * GET /api/v1/referral/stats
     * Возвращает статистику реферальной программы для авторизованного пользователя.
     * Если кода ещё нет — создаёт его. Идемпотентно.
     */
    @GetMapping("/stats")
    fun getStats(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<ReferralStatsDto> {
        val stats = referralService.getStats(user)
        return ResponseEntity.ok(stats)
    }
}