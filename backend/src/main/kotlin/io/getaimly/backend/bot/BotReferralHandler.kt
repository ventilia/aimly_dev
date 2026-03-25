package io.getaimly.backend.bot

import io.getaimly.backend.referral.ReferralService
import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory


class BotReferralHandler(
    private val sender: BotSender,
    private val userRepository: UserRepository,
    private val referralService: ReferralService,
    private val expiryRepository: SubscriptionExpiryRepository,
) {

    private val log = LoggerFactory.getLogger(BotReferralHandler::class.java)


    fun showReferral(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][REFERRAL] Не авторизован: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        val stats  = referralService.getStats(user)
        val expiry = expiryRepository.findByUserId(user.id)

        log.info(
            "[BOT][REFERRAL] Просмотр: userId=${user.id} email=${user.email} " +
                    "всего=${stats.totalReferrals} оплатили=${stats.paidReferrals} буфер=${stats.bonusDaysLeft}"
        )

        // Строка о бонусном буфере
        val bufferLine = when {
            stats.bonusDaysLeft > 0 ->
                "\n\n🎁 *Бонусных дней в запасе:* ${stats.bonusDaysLeft} дн\\.\n" +
                        "_Используются автоматически если автоплатёж не пройдёт_"
            else ->
                "\n\n🎁 *Бонусных дней в запасе:* нет"
        }

        // Строка о текущей подписке
        val subLine = when {
            expiry != null && user.subscriptionStatus in setOf("ACTIVE", "TRIAL") ->
                "\n📅 Подписка до: ${expiry.expiresAt.toLocalDate()}"
            else -> ""
        }

        val text =
            "👥 *Реферальная программа*\n\n" +
                    "Приглашайте друзей и коллег в AIMLY\\.\n" +
                    "За каждого, кто оплатит подписку по вашей ссылке — вы получите *+5 дней* бонуса\\.\n\n" +
                    "─────────────────────\n" +
                    "📊 *Ваша статистика:*\n" +
                    "👤 Перешли по ссылке: *${stats.totalReferrals}*\n" +
                    "💳 Оплатили подписку: *${stats.paidReferrals}*" +
                    subLine +
                    bufferLine + "\n\n" +
                    "─────────────────────\n" +
                    "🔗 *Ваша реферальная ссылка:*\n" +
                    "`${stats.referralLink}`\n\n" +
                    "_Нажмите на ссылку выше — она скопируется_"

        sender.editText(
            chatId, msgId,
            text,
            markup = keyboard(
                row(btn("◀️ Назад к профилю", "menu:profile")),
            ),
            parseMarkdown = true,
        )
    }
}