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

        log.info("[SUB] Выдана вручную: userId=${user.id} email=${user.email} plan=$plan до=$expiresAt дней=${req.durationDays}")

        user.telegramId?.let { tgId ->
            runCatching { bot.sendText(tgId, buildGrantMessage(plan, expiresAt)) }
                .onFailure { log.warn("telegram notify grant userId=${user.id}: ${it.message}") }
        }

        return user.toDto()
    }


    @Transactional
    fun grantTrial(user: User) {
        if (user.trialUsed) {
            log.debug("пропускаем trial для ${user.email} — trialUsed=true")
            return
        }
        if (user.subscriptionStatus != null && user.subscriptionStatus !in setOf("INACTIVE")) {
            log.debug("пропускаем trial для ${user.email} — статус ${user.subscriptionStatus}")
            return
        }

        val expiresAt = LocalDateTime.now().plusDays(7)

        user.trialUsed          = true
        user.subscriptionStatus = "TRIAL"
        user.subscriptionPlan   = "START"
        userRepository.save(user)

        val expiry = expiryRepository.findByUserId(user.id)
            ?.apply { this.expiresAt = expiresAt; this.notifiedRenewal = false }
            ?: SubscriptionExpiry(user = user, expiresAt = expiresAt)
        expiryRepository.save(expiry)

        log.info("[SUB] Trial выдан: userId=${user.id} email=${user.email} до=$expiresAt")

        user.telegramId?.let { tgId ->
            runCatching { bot.sendText(tgId, buildGrantMessage("TRIAL", expiresAt)) }
                .onFailure { log.warn("telegram notify trial userId=${user.id}: ${it.message}") }
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

        log.info("[SUB] Тариф изменён: userId=${user.id} email=${user.email} $oldPlan/$oldStatus → $plan/$status")

        if (oldPlan != plan && status == "ACTIVE") {
            user.telegramId?.let { tgId ->
                runCatching {
                    bot.sendText(tgId, "📋 Ваш тариф изменён на $plan.\n${planFeatures(plan)}")
                }.onFailure { log.warn("telegram notify changePlan userId=${user.id}: ${it.message}") }
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

        log.info("[SUB] Продлена вручную: userId=${user.id} email=${user.email} plan=$plan до=$expiresAt дней=${req.durationDays}")
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

        log.info("[SUB] Отозвана: userId=${user.id} email=${user.email} был план=$oldPlan")

        user.telegramId?.let { tgId ->
            runCatching {
                bot.sendText(tgId, "⚠️ Ваша подписка AIMLY деактивирована. Напишите в поддержку для продления.")
            }.onFailure { log.warn("telegram notify revoke userId=${user.id}: ${it.message}") }
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
                    val date   = expiry.expiresAt.toLocalDate()
                    val buffer = expiry.bonusDaysBuffer

                    val bufferLine = if (buffer > 0)
                        "\n\n🎁 У вас есть $buffer бонусных дней — они автоматически продлят доступ если платёж не пройдёт."
                    else ""

                    val msg = if (isTrial)
                        "⏰ Пробный период AIMLY истекает $date.\n\nОформите подписку, чтобы не потерять доступ:$bufferLine"
                    else
                        "⏰ Подписка AIMLY истекает $date.\n\nПродлите подписку, чтобы не прерывать мониторинг:$bufferLine"

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

                    log.info("[SUB] Уведомление об истечении: userId=${user.id} email=${user.email} дата=$date isTrial=$isTrial буфер=$buffer")
                }.onFailure { log.warn("notify expiring userId=${user.id}: ${it.message}") }
            }
        }
    }


    // ─── Главная логика буфера ────────────────────────────────────────────────
    // Сценарий A (нормальный): Tribute продлил → renewed_subscription → expiresAt обновлён →
    //   deactivateExpired() не видит эту запись (она не истекла) → буфер не тронут.
    //
    // Сценарий B (автоплатёж не прошёл): expiresAt < now, Tribute молчит →
    //   deactivateExpired() видит запись → если bonusDaysBuffer > 0 →
    //   продлеваем expiresAt на весь буфер, обнуляем буфер, уведомляем пользователя.
    //   Если Tribute всё равно не продлит в эти дни — после истечения буфера
    //   подписка деактивируется стандартно.
    //
    // Сценарий C (буфер истощён): expiresAt < now, буфер = 0 → деактивация как раньше.

    fun deactivateExpired() {
        expiryRepository.findExpired(LocalDateTime.now()).forEach { expiry ->
            val user     = expiry.user
            val wasTrial = user.subscriptionStatus == "TRIAL"

            if (user.subscriptionStatus !in setOf("ACTIVE", "TRIAL")) return@forEach

            // Сценарий B: есть бонусный буфер — используем его вместо деактивации
            if (expiry.bonusDaysBuffer > 0) {
                val daysToAdd      = expiry.bonusDaysBuffer
                val newExpiresAt   = LocalDateTime.now().plusDays(daysToAdd.toLong())
                expiry.expiresAt       = newExpiresAt
                expiry.bonusDaysBuffer = 0
                expiry.notifiedRenewal = false
                expiryRepository.save(expiry)

                log.info(
                    "[SUB] Буфер бонусных дней использован: userId=${user.id} email=${user.email} " +
                            "дней=$daysToAdd новая_дата=$newExpiresAt"
                )

                user.telegramId?.let { tgId ->
                    runCatching {
                        val markup = InlineKeyboardMarkup(listOf(
                            InlineKeyboardRow(listOf(
                                InlineKeyboardButton.builder()
                                    .text("💳 Продлить подписку")
                                    .url(BotPaymentHandler.TRIBUTE_BOT_URL)
                                    .build()
                            ))
                        ))
                        bot.sendText(
                            tgId,
                            "🎁 Автоплатёж не прошёл, но у вас были бонусные дни!\n\n" +
                                    "Мы автоматически продлили доступ на $daysToAdd дн.\n" +
                                    "📅 Доступ до: ${newExpiresAt.toLocalDate()}\n\n" +
                                    "Пожалуйста, обновите способ оплаты, чтобы не потерять доступ:",
                            markup,
                        )
                    }.onFailure { log.warn("notify buffer used userId=${user.id}: ${it.message}") }
                }
                return@forEach
            }

            // Сценарий C: буфер пуст — стандартная деактивация
            user.subscriptionStatus = "INACTIVE"
            user.subscriptionPlan   = null
            userRepository.save(user)

            log.info("[SUB] Истекла: userId=${user.id} email=${user.email} был=${if (wasTrial) "TRIAL" else "ACTIVE"}")

            user.telegramId?.let { tgId ->
                runCatching {
                    val msg = if (wasTrial)
                        "Пробный период закончился.\n\n" +
                                "Вы попробовали AIMLY — теперь можно оформить полный доступ и продолжить получать лиды из Telegram.\n\n" +
                                "Тариф START — 4 990 ₽/мес:\n" +
                                "✔ Мониторинг чатов по ключевым словам\n" +
                                "✔ Лиды без ограничений\n" +
                                "✔ AI-поиск и фильтрация лидов\n" +
                                "✔ Персонализация под ваш бизнес"
                    else
                        "Подписка AIMLY закончилась.\n\n" +
                                "Мониторинг приостановлен. Чтобы снова получать лиды — продлите подписку.\n\n" +
                                "Тариф START — 4 990 ₽/мес:\n" +
                                "✔ Мониторинг чатов по ключевым словам\n" +
                                "✔ Лиды без ограничений\n" +
                                "✔ AI-поиск и фильтрация лидов\n" +
                                "✔ Персонализация под ваш бизнес"

                    val markup = InlineKeyboardMarkup(listOf(
                        InlineKeyboardRow(listOf(
                            InlineKeyboardButton.builder()
                                .text("💳 Оформить подписку")
                                .url(BotPaymentHandler.TRIBUTE_BOT_URL)
                                .build()
                        ))
                    ))

                    bot.sendText(tgId, msg, markup)
                }.onFailure { log.warn("notify expired userId=${user.id}: ${it.message}") }
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
        else -> "В разработке"
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