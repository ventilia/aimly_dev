package io.getaimly.backend.subscription

import io.getaimly.backend.auth.NotFoundException
import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.bot.BotPaymentHandler
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import java.time.LocalDateTime


data class SubscriptionDto(
    val userId:    Long,
    val email:     String,
    val firstName: String?,
    val status:    String?,
    val plan:      String?,
    val expiresAt: String?,
)


data class GrantSubscriptionRequest(
    val userId:       Long,
    val plan:         String,
    val durationDays: Int = 30,
)


data class ChangePlanRequest(
    val userId: Long,
    val plan:   String,
    val status: String = "ACTIVE",
)

data class SetExpiryRequest(
    val userId:       Long,
    val plan:         String,
    val durationDays: Int,
)


// Реальные тарифы: START и BUSINESS.
// TRIAL — это пробный период, он по факту даёт возможности START.
// "MINIMUM" не существует и нигде не используется.
private val VALID_PLANS = setOf("START", "BUSINESS", "TRIAL")


@Service
class SubscriptionService(
    private val userRepository:   UserRepository,
    private val expiryRepository: SubscriptionExpiryRepository,
    private val bot:              AimlyBot,
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
        if (plan == "TRIAL") user.trialUsed = true

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
        // Не выдаём trial повторно
        if (user.trialUsed) {
            log.debug("пропускаем trial для ${user.email} — trialUsed=true")
            return
        }
        // Не перебиваем уже активную подписку
        if (user.subscriptionStatus != null && user.subscriptionStatus !in setOf("INACTIVE")) {
            log.debug("пропускаем trial для ${user.email} — статус ${user.subscriptionStatus}")
            return
        }

        val expiresAt = LocalDateTime.now().plusDays(5)

        user.trialUsed          = true
        user.subscriptionStatus = "TRIAL"
        // Trial даёт те же возможности что START — план START, статус TRIAL
        user.subscriptionPlan   = "START"
        userRepository.save(user)

        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)

        log.info("trial выдан пользователю ${user.email}, истекает $expiresAt")

        user.telegramId?.let { tgId ->
            runCatching {
                bot.sendText(tgId, buildGrantMessage("TRIAL", expiresAt))
            }.onFailure { log.warn("telegram notify trial: ${it.message}") }
        }
    }


    @Transactional
    fun changePlan(req: ChangePlanRequest): SubscriptionDto {
        val plan   = req.plan.uppercase()
        val status = req.status.uppercase()
        if (plan !in setOf("START", "BUSINESS"))
            throw IllegalArgumentException("план должен быть START или BUSINESS")
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
                bot.sendText(tgId, "⚠️ Ваша подписка AIMLY деактивирована. Напишите в поддержку для продления.")
            }.onFailure { }
        }

        return user.toDto()
    }


    fun notifyExpiring() {
        val threshold = LocalDateTime.now().plusDays(3)
        expiryRepository.findExpiringAndNotNotified(threshold).forEach { expiry ->
            val user    = expiry.user
            val isTrial = user.subscriptionStatus == "TRIAL"
            user.telegramId?.let { tgId ->
                runCatching {
                    val date = expiry.expiresAt.toLocalDate()
                    val msg = if (isTrial)
                        "⏰ Пробный период AIMLY истекает $date.\n\nОформите подписку, чтобы не потерять доступ:"
                    else
                        "⏰ Подписка AIMLY истекает $date.\n\nПродлите подписку, чтобы не прерывать мониторинг:"

                    val markup = InlineKeyboardMarkup(listOf(
                        InlineKeyboardRow(listOf(
                            InlineKeyboardButton.builder()
                                .text("💳 Перейти к оплате")
                                .url(BotPaymentHandler.TRIBUTE_BOT_URL)
                                .build()
                        ))
                    ))

                    bot.sendText(tgId, msg, markup)
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
                user.subscriptionPlan   = null
                userRepository.save(user)
                log.info("подписка истекла: ${user.email} (был ${if (wasTrial) "TRIAL" else "ACTIVE"})")
                user.telegramId?.let { tgId ->
                    runCatching {
                        bot.sendText(
                            tgId,
                            if (wasTrial)
                                "❌ Пробный период AIMLY истёк.\nЧтобы продолжить — свяжитесь с поддержкой: @aimly_support"
                            else
                                "❌ Подписка AIMLY истекла. Свяжитесь с поддержкой для продления: @aimly_support"
                        )
                    }.onFailure { }
                }
            }
        }
    }


    private fun buildGrantMessage(plan: String, expiresAt: LocalDateTime) = when (plan) {
        "TRIAL" ->
            "🎉 Пробный период AIMLY активирован!\n" +
                    "✅ 5 дней бесплатно — все функции включая AI\n" +
                    "✅ AI-семантический поиск лидов\n" +
                    "✅ AI-фильтрация контекста сообщений\n" +
                    "✅ Персонализация под ваш бизнес\n" +
                    "Действует до: ${expiresAt.toLocalDate()}"
        "START" ->
            "🎉 Подписка AIMLY START активирована!\n" +
                    "✅ Мониторинг по ключевым словам\n" +
                    "✅ Лиды без ограничений\n" +
                    "✅ AI-семантический поиск лидов\n" +
                    "✅ AI-фильтрация контекста сообщений\n" +
                    "✅ Персонализация под ваш бизнес\n" +
                    "✅ Уведомления в Telegram\n" +
                    "Действует до: ${expiresAt.toLocalDate()}"
        else ->
            "🚀 Подписка AIMLY BUSINESS активирована!\n" +
                    "Действует до: ${expiresAt.toLocalDate()}"
    }


    private fun planFeatures(plan: String) = when (plan) {
        "START" ->
            "✅ Мониторинг по ключевым словам\n" +
                    "✅ Лиды без ограничений\n" +
                    "✅ AI-семантический поиск лидов\n" +
                    "✅ AI-фильтрация контекста сообщений\n" +
                    "✅ Персонализация под ваш бизнес\n" +
                    "✅ Уведомления в Telegram"
        "BUSINESS" ->
            "✅ Все функции тарифа START\n" +
                    "✅ Расширенные возможности"
        else ->
            "В разработке"
    }


    private fun User.toDto() = SubscriptionDto(
        userId    = id,
        email     = email,
        firstName = firstName,
        status    = subscriptionStatus,
        plan      = subscriptionPlan,
        expiresAt = expiryRepository.findByUserId(id)?.expiresAt?.toString(),
    )
}