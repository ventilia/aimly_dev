package io.getaimly.backend.subscription

import io.getaimly.backend.auth.BadRequestException
import io.getaimly.backend.auth.NotFoundException
import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime


private const val DURATION_DAYS = 30

data class PurchaseRequest(
    val plan: String,
)

data class PurchaseResponse(
    val plan:      String,
    val expiresAt: String,
)


@RestController
@RequestMapping("/api/v1/subscriptions")
class UserSubscriptionController(private val service: UserSubscriptionService) {

    @PostMapping("/purchase")
    fun purchase(
        @AuthenticationPrincipal user: User,
        @RequestBody req: PurchaseRequest,
    ): ResponseEntity<PurchaseResponse> =
        ResponseEntity.ok(service.purchase(user, req))
}


@Service
class UserSubscriptionService(
    private val userRepository:   UserRepository,
    private val expiryRepository: SubscriptionExpiryRepository,
    private val bot:              AimlyBot,
) {
    private val log = LoggerFactory.getLogger(UserSubscriptionService::class.java)

    @Transactional
    fun purchase(caller: User, req: PurchaseRequest): PurchaseResponse {
        val plan = req.plan.uppercase()
        if (plan !in setOf("START", "BUSINESS")) {
            throw BadRequestException("неизвестный тариф: ${req.plan}")
        }

        val user = userRepository.findById(caller.id)
            .orElseThrow { NotFoundException("пользователь не найден") }

        val existing  = expiryRepository.findByUserId(user.id)
        val base      = if (
            existing != null &&
            existing.expiresAt.isAfter(LocalDateTime.now()) &&
            user.subscriptionStatus in setOf("ACTIVE", "TRIAL")
        ) existing.expiresAt else LocalDateTime.now()

        val expiresAt = base.plusDays(DURATION_DAYS.toLong())

        user.subscriptionStatus = "ACTIVE"
        user.subscriptionPlan   = plan
        userRepository.save(user)

        val expiry = existing
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)

        log.info("[SUB] Куплена (сайт): userId=${user.id} email=${user.email} plan=$plan до=$expiresAt")

        user.telegramId?.let { tgId ->
            runCatching {
                bot.sendText(tgId, "✅ Тариф $plan активирован!\n\nДействует до: ${expiresAt.toLocalDate()}")
            }.onFailure { log.warn("telegram notify purchase userId=${user.id}: ${it.message}") }
        }

        return PurchaseResponse(
            plan      = plan,
            expiresAt = expiresAt.toString(),
        )
    }
}