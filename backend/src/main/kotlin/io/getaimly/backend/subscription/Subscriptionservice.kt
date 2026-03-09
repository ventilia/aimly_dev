package io.getaimly.backend.subscription

import io.getaimly.backend.auth.NotFoundException
import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime


data class SubscriptionDto(
    val userId: Long,
    val email: String,
    val firstName: String?,
    val status: String?,
    val plan: String?,
    val expiresAt: String?,
    val balance: Int,
)


data class GrantSubscriptionRequest(
    val userId: Long,
    val plan: String,
    val durationDays: Int = 30,
)


data class ChangePlanRequest(
    val userId: Long,
    val plan: String,
    val status: String = "ACTIVE",
)

// изменить дату окончания подписки
data class SetExpiryRequest(
    val userId: Long,
    val plan: String,
    val durationDays: Int,
)

data class AdjustBalanceRequest(
    val userId: Long,
    val amount: Int,
)


private val VALID_PLANS = setOf("MINIMUM", "START", "TRIAL")


@Service
class SubscriptionService(
    private val userRepository: UserRepository,
    private val expiryRepository: SubscriptionExpiryRepository,
    private val bot: AimlyBot,
) {
    private val log = LoggerFactory.getLogger(SubscriptionService::class.java)

    fun getAll(): List<SubscriptionDto> =
        userRepository.findAll().map { it.toDto() }


    @Transactional
    fun grant(req: GrantSubscriptionRequest): SubscriptionDto {
        val plan = req.plan.uppercase()
        if (plan !in VALID_PLANS) throw IllegalArgumentException("неизвестный план: ${req.plan}")
        val user = userRepository.findById(req.userId)
            .orElseThrow { NotFoundException("пользователь не найден") }
        val expiresAt = LocalDateTime.now().plusDays(req.durationDays.toLong())
        user.subscriptionStatus = if (plan == "TRIAL") "TRIAL" else "ACTIVE"
        user.subscriptionPlan   = plan
        userRepository.save(user)
        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)
        log.info("подписка $plan выдана пользователю ${user.email} до $expiresAt")
        user.telegramId?.let { tgId ->
            runCatching { bot.sendText(tgId, buildGrantMessage(plan, expiresAt)) }
                .onFailure { log.warn("telegram notify grant: ${it.message}") }
        }
        return user.toDto()
    }


    @Transactional
    fun grantTrial(user: User) {
        if (user.subscriptionStatus != null && user.subscriptionStatus !in setOf("INACTIVE")) {
            log.debug("пропускаем trial для ${user.email} — статус ${user.subscriptionStatus}")
            return
        }
        val expiresAt = LocalDateTime.now().plusDays(7)
        user.subscriptionStatus = "TRIAL"
        user.subscriptionPlan   = "START"   // trial даёт полный START
        userRepository.save(user)
        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)
        log.info("trial выдан пользователю ${user.email}, истекает $expiresAt")
    }


    @Transactional
    fun changePlan(req: ChangePlanRequest): SubscriptionDto {
        val plan   = req.plan.uppercase()
        val status = req.status.uppercase()
        if (plan !in setOf("MINIMUM", "START"))
            throw IllegalArgumentException("план должен быть MINIMUM или START")
        if (status !in setOf("ACTIVE", "INACTIVE"))
            throw IllegalArgumentException("статус должен быть ACTIVE или INACTIVE")
        val user = userRepository.findById(req.userId)
            .orElseThrow { NotFoundException("пользователь не найден") }
        val oldPlan   = user.subscriptionPlan
        val oldStatus = user.subscriptionStatus
        user.subscriptionPlan   = plan
        user.subscriptionStatus = status
        userRepository.save(user)
        log.info("смена плана ${user.email}: $oldPlan/$oldStatus → $plan/$status")
        if (oldPlan != plan && status == "ACTIVE") {
            user.telegramId?.let { tgId ->
                runCatching {
                    bot.sendText(tgId, "📋 Ваш тариф изменён на $plan.\n${planFeatures(plan)}")
                }.onFailure { log.warn("telegram notify changePlan: ${it.message}") }
            }
        }
        return user.toDto()
    }


    @Transactional
    fun setExpiry(req: SetExpiryRequest): SubscriptionDto {
        val plan = req.plan.uppercase()
        if (plan !in VALID_PLANS) throw IllegalArgumentException("неизвестный план: ${req.plan}")
        val user = userRepository.findById(req.userId)
            .orElseThrow { NotFoundException("пользователь не найден") }
        val expiresAt = LocalDateTime.now().plusDays(req.durationDays.toLong())

        if (user.subscriptionStatus == "INACTIVE") {
            user.subscriptionStatus = "ACTIVE"
        }
        user.subscriptionPlan = plan
        userRepository.save(user)
        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)
        log.info("срок подписки изменён для ${user.email} до $expiresAt")
        return user.toDto()
    }


    @Transactional
    fun revoke(userId: Long): SubscriptionDto {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("пользователь не найден") }
        val oldPlan = user.subscriptionPlan
        user.subscriptionStatus = "INACTIVE"
        user.subscriptionPlan   = null
        userRepository.save(user)
        log.info("подписка отозвана: ${user.email} (был план: $oldPlan)")
        user.telegramId?.let { tgId ->
            runCatching {
                bot.sendText(tgId, "⚠️ Ваша подписка AIMLY деактивирована. Напишите менеджеру для продления.")
            }.onFailure { }
        }
        return user.toDto()
    }


    @Transactional
    fun adjustBalance(req: AdjustBalanceRequest): SubscriptionDto {
        val user = userRepository.findById(req.userId)
            .orElseThrow { NotFoundException("пользователь не найден") }
        val newBalance = user.balance + req.amount
        if (newBalance < 0) throw IllegalArgumentException("баланс не может быть отрицательным")
        user.balance = newBalance
        userRepository.save(user)
        return user.toDto()
    }

    fun notifyExpiring() {
        val threshold = LocalDateTime.now().plusDays(3)
        expiryRepository.findExpiringAndNotNotified(threshold).forEach { expiry ->
            val user    = expiry.user
            val isTrial = user.subscriptionStatus == "TRIAL"
            user.telegramId?.let { tgId ->
                runCatching {
                    val msg = if (isTrial)
                        "⏰ Пробный период AIMLY истекает ${expiry.expiresAt.toLocalDate()}.\n" +
                                "Для продолжения оформите подписку: t.me/yar0309"
                    else
                        "⏰ Подписка AIMLY истекает ${expiry.expiresAt.toLocalDate()}.\n" +
                                "Свяжитесь с менеджером для продления."
                    bot.sendText(tgId, msg)
                    expiry.notifiedRenewal = true
                    expiryRepository.save(expiry)
                }.onFailure { log.warn("notify expiring: ${it.message}") }
            }
        }
    }

    fun deactivateExpired() {
        expiryRepository.findExpired(LocalDateTime.now()).forEach { expiry ->
            val user     = expiry.user
            val wasTrial = user.subscriptionStatus == "TRIAL"
            if (user.subscriptionStatus in setOf("ACTIVE", "TRIAL")) {
                user.subscriptionStatus = "INACTIVE"
                user.subscriptionPlan   = null  // очищаем план при истечении
                userRepository.save(user)
                log.info("подписка истекла: ${user.email} (был ${if (wasTrial) "TRIAL" else "ACTIVE"})")
                user.telegramId?.let { tgId ->
                    runCatching {
                        bot.sendText(tgId,
                            if (wasTrial)
                                "❌ Пробный период AIMLY истёк.\nОформите подписку: t.me/yar0309"
                            else
                                "❌ Подписка AIMLY истекла. Напишите менеджеру для продления."
                        )
                    }.onFailure { }
                }
            }
        }
    }


    private fun buildGrantMessage(plan: String, expiresAt: LocalDateTime) = when (plan) {
        "TRIAL"   ->
            "🎉 Пробный период AIMLY активирован!\n" +
                    "✅ 7 дней бесплатно — все функции START\n" +
                    "Действует до: ${expiresAt.toLocalDate()}\nДля продолжения: t.me/yar0309"
        "MINIMUM" ->
            "🎉 Подписка AIMLY MINIMUM активирована!\n" +
                    "✅ Мониторинг по ключевым словам\n" +
                    "✅ Лиды без ограничений\n" +
                    "✅ Уведомления в Telegram\n" +
                    "Действует до: ${expiresAt.toLocalDate()}"
        else      -> // START
            "🚀 Подписка AIMLY START активирована!\n" +
                    "✅ Все функции без ограничений\n" +
                    "✅ AI-фильтрация нерелевантных лидов\n" +
                    "✅ Семантический поиск и синонимы\n" +
                    "✅ Персонализация под ваш бизнес\n" +
                    "Действует до: ${expiresAt.toLocalDate()}"
    }


    private fun planFeatures(plan: String) = when (plan) {
        "MINIMUM" ->
            "✅ Мониторинг по ключевым словам\n" +
                    "✅ Лиды без ограничений\n" +
                    "✅ Уведомления в Telegram"
        else ->
            "✅ Всё из тарифа Минимум\n" +
                    "✅ AI-фильтрация нерелевантных лидов\n" +
                    "✅ Семантический поиск и синонимы\n" +
                    "✅ Персонализация под бизнес"
    }

    private fun User.toDto() = SubscriptionDto(
        userId    = id,
        email     = email,
        firstName = firstName,
        status    = subscriptionStatus,
        plan      = subscriptionPlan,
        expiresAt = expiryRepository.findByUserId(id)?.expiresAt?.toString(),
        balance   = balance,
    )
}