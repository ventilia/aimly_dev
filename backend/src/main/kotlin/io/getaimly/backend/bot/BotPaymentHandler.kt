package io.getaimly.backend.bot

import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory


class BotPaymentHandler(
    private val sender: BotSender,
    private val userRepository: UserRepository,
    private val expiryRepository: SubscriptionExpiryRepository,
) {

    private val log = LoggerFactory.getLogger(BotPaymentHandler::class.java)

    companion object {

        const val TRIBUTE_BOT_URL =
            "https://t.me/tribute/app?startapp=ep_8xkUDdkgdcdIKFpXiZ9B7LGSk6x4TbxwNNWPQSFjRxqZQnNIZq"
    }



    fun showPlans(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val expiry  = expiryRepository.findByUserId(user.id)
        val tillStr = expiry?.expiresAt?.toLocalDate()?.toString() ?: "—"

        val text = when (user.subscriptionStatus) {
            "ACTIVE" ->
                "💳 *Управление подпиской*\n\n" +
                        "Тариф: *${user.subscriptionPlan ?: "—"}*\n" +
                        "Активна до: *$tillStr*\n\n" +
                        "Для продления или смены тарифа нажмите кнопку ниже\\."
            "TRIAL"  ->
                "💳 *Пробный период активен*\n\n" +
                        "Действует до: *$tillStr*\n\n" +
                        "Оформите подписку заранее, чтобы не потерять доступ\\."
            else     ->
                "💳 *Тарифы AIMLY*\n\n" +
                        "*START* — 4 990 ₽/мес\n" +
                        "✔ Мониторинг Telegram\\-чатов\n" +
                        "✔ Ключевые слова без ограничений\n" +
                        "✔ Лиды без ограничений\n" +
                        "✔ AI\\-семантический поиск лидов\n" +
                        "✔ AI\\-фильтрация контекста сообщений\n" +
                        "✔ Персонализация под ваш бизнес\n" +
                        "✔ Уведомления в Telegram\n\n" +
                        "После оплаты подписка активируется автоматически\\."
        }

        sender.editText(
            chatId, msgId,
            text,
            markup = keyboard(
                row(urlBtn("💳 Перейти к оплате", TRIBUTE_BOT_URL)),
                row(btn("◀️ Главное меню", "menu:back")),
            ),
            parseMarkdown = true,
        )

        log.info("showPlans: tgUserId=$tgUserId subscriptionStatus=${user.subscriptionStatus}")
    }


    fun sendPaymentMessage(chatId: Long, tgUserId: Long) {
        val user    = userRepository.findByTelegramId(tgUserId).orElse(null)
        val expiry  = user?.let { expiryRepository.findByUserId(it.id) }
        val tillStr = expiry?.expiresAt?.toLocalDate()?.toString()

        val text = when {
            user?.subscriptionStatus == "ACTIVE" && tillStr != null ->
                "💳 *Управление подпиской*\n\n" +
                        "Тариф: *${user.subscriptionPlan ?: "—"}*\n" +
                        "Активна до: *$tillStr*\n\n" +
                        "Для продления нажмите кнопку:"
            user?.subscriptionStatus == "TRIAL" && tillStr != null ->
                "💳 *Пробный период активен до $tillStr*\n\n" +
                        "Оформите подписку, чтобы не потерять доступ после окончания:"
            else ->
                "💳 *Тарифы AIMLY*\n\n" +
                        "*Минимум* — 4 990 ₽/мес\n" +
                        "✔ Мониторинг чатов, AI\\-поиск лидов, уведомления в Telegram\n\n" +
                        "После оплаты подписка активируется автоматически."
        }

        sender.sendText(
            chatId,
            text,
            markup = keyboard(
                row(urlBtn("💳 Перейти к оплате", TRIBUTE_BOT_URL)),
            ),
            parseMarkdown = true,
        )

        log.info("sendPaymentMessage: chatId=$chatId tgUserId=$tgUserId")
    }
}