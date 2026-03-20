package io.getaimly.backend.bot

import io.getaimly.backend.auth.AuthService
import io.getaimly.backend.auth.BadRequestException
import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.auth.LoginResult
import io.getaimly.backend.auth.TooManyRequestsException
import io.getaimly.backend.auth.UnauthorizedException
import io.getaimly.backend.auth.dto.LoginRequest
import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.lead.LeadStatus
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.objects.User
import java.util.concurrent.ConcurrentHashMap


class BotAuthHandler(
    private val sender: BotSender,
    private val sessions: ConcurrentHashMap<Long, UserSession>,
    private val userRepository: UserRepository,
    private val leadRepository: LeadRepository,
    private val authService: AuthService,
    private val paymentHandler: BotPaymentHandler,
) {

    private val log = LoggerFactory.getLogger(BotAuthHandler::class.java)

    companion object {
        const val SITE_URL       = "https://getaimly.io"
        const val SITE_DASHBOARD = "$SITE_URL/dashboard"
        private const val PAY_PREFIX = "pay_"
    }


    fun handleStart(chatId: Long, from: User, linkToken: String?) {
        if (linkToken != null) {
            handleLinkToken(chatId, from, linkToken)
            return
        }
        val existing = userRepository.findByTelegramId(from.id).orElse(null)
        if (existing != null) {
            showMainMenu(chatId, existing.firstName)
        } else {
            showWelcome(chatId, from.firstName)
        }
    }

    private fun handleLinkToken(chatId: Long, from: User, token: String) {


        if (token.startsWith(PAY_PREFIX)) {
            val realToken = token.removePrefix(PAY_PREFIX)
            val linked    = authService.linkTelegram(realToken, from.id, from.userName)

            if (linked) {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                val name = user?.firstName ?: from.firstName ?: "там"
                sender.sendText(
                    chatId,
                    "✅ *Telegram привязан к аккаунту AIMLY!*\n\n" +
                            "Теперь переходим к оплате подписки.",
                    parseMarkdown = true,
                )
                paymentHandler.sendPaymentMessage(chatId, from.id)
                log.info("pay_token: привязан и отправлена оплата, tgId=${from.id}")
            } else {
                val existing = userRepository.findByTelegramId(from.id).orElse(null)
                if (existing != null) {
                    sender.sendText(
                        chatId,
                        "Ссылка привязки устарела, но вы уже авторизованы. Вот кнопка оплаты:",
                    )
                    paymentHandler.sendPaymentMessage(chatId, from.id)
                } else {
                    sender.sendText(
                        chatId,
                        "Ссылка привязки устарела.\n\n" +
                                "Войдите через сайт и повторите переход к оплате:\n" +
                                "🌐 $SITE_URL/checkout",
                    )
                }
            }
            return
        }

        if (token == "pay") {
            val existing = userRepository.findByTelegramId(from.id).orElse(null)
            if (existing != null) {
                paymentHandler.sendPaymentMessage(chatId, from.id)
            } else {
                sessions[chatId] = UserSession(
                    step          = BotStep.WAITING_EMAIL,
                    msgId         = 0,
                    pendingAction = "pay",
                )
                sender.sendText(
                    chatId,
                    "Для оплаты подписки войдите в аккаунт AIMLY.\n\nВведите email:",
                    markup        = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
                )
            }
            return
        }

        val ok = authService.linkTelegram(token, from.id, from.userName)
        if (ok) {
            val user = userRepository.findByTelegramId(from.id).orElse(null)
            val name = user?.firstName ?: from.firstName ?: "там"
            sender.sendText(
                chatId,
                "✅ *Telegram успешно привязан к аккаунту AIMLY\\!*\n\n" +
                        "Теперь новые лиды будут приходить сюда автоматически\\.\n\n" +
                        "🌐 Для удобного управления рекомендуем использовать сайт:\n" +
                        "$SITE_DASHBOARD\n\n" +
                        "📌 Чтобы начать мониторинг прямо здесь:\n" +
                        "  1\\. Добавьте чаты → кнопка «💬 Чаты»\n" +
                        "  2\\. Укажите ключевые слова → «🔍 Ключевые слова»",
                parseMarkdown = true,
            )
            showMainMenu(chatId, name)
        } else {
            sender.sendText(
                chatId,
                "❌ Ссылка недействительна или истекла.\n\n" +
                        "Запросите новую ссылку в личном кабинете:\n" +
                        "🌐 $SITE_DASHBOARD → Профиль → Привязать Telegram",
            )
        }
    }

    fun showWelcome(chatId: Long, name: String?) {
        sender.sendText(
            chatId,
            "👋 Привет, ${name ?: "там"}!\n\n" +
                    "*AIMLY* — сервис поиска лидов в Telegram-чатах по ключевым словам.\n\n" +
                    "🤖 Этот бот повторяет весь функционал сайта \\(кроме администрирования\\)\\.\n" +
                    "Для максимального удобства рекомендуем использовать веб-версию:\n" +
                    "🌐 $SITE_URL\n\n" +
                    "Войдите в аккаунт, чтобы начать:",
            markup        = keyboard(row(btn("🔐 Войти в аккаунт", "auth:login"))),
            parseMarkdown = true,
        )
    }

    fun showMainMenu(chatId: Long, name: String?) {
        val user       = userRepository.findByTelegramId(chatId).orElse(null)
        val newCount   = user?.let { leadRepository.countByUserIdAndStatus(it.id, LeadStatus.NEW) } ?: 0
        val leadsLabel = if (newCount > 0) "📬 Лиды  •  $newCount новых" else "📋 Лиды"

        val rows = mutableListOf(
            row(btn(leadsLabel,          "menu:leads")),
            row(btn("💬 Чаты",           "menu:chats"),
                btn("🔍 Ключевые слова",  "menu:keywords")),
            row(btn("👤 Профиль",         "menu:profile"),
                btn("❓ Помощь",          "menu:help")),
        )

        if (user?.subscriptionStatus != "ACTIVE") {
            rows.add(row(btn("💳 Оплатить подписку", "payment:plans")))
        }

        sender.sendText(
            chatId,
            "👋 Привет, ${name ?: "там"}!\n\nЧто хотите сделать?",
            markup = keyboard(*rows.toTypedArray()),
        )
    }

    fun showMainMenuEdit(chatId: Long, msgId: Int, from: User) {
        val user       = userRepository.findByTelegramId(from.id).orElse(null)
        val newCount   = user?.let { leadRepository.countByUserIdAndStatus(it.id, LeadStatus.NEW) } ?: 0
        val leadsLabel = if (newCount > 0) "📬 Лиды  •  $newCount новых" else "📋 Лиды"

        val rows = mutableListOf(
            row(btn(leadsLabel,          "menu:leads")),
            row(btn("💬 Чаты",           "menu:chats"),
                btn("🔍 Ключевые слова",  "menu:keywords")),
            row(btn("👤 Профиль",         "menu:profile"),
                btn("❓ Помощь",          "menu:help")),
        )

        if (user?.subscriptionStatus != "ACTIVE") {
            rows.add(row(btn("💳 Оплатить подписку", "payment:plans")))
        }

        sender.editText(
            chatId, msgId,
            "👋 Привет, ${user?.firstName ?: "там"}!\n\nЧто хотите сделать?",
            markup = keyboard(*rows.toTypedArray()),
        )
    }


    fun startLoginFlow(chatId: Long, msgId: Int) {
        sessions[chatId] = UserSession(step = BotStep.WAITING_EMAIL, msgId = msgId)
        sender.editText(
            chatId, msgId,
            "🔐 *Вход в аккаунт*\n\nВведите email от вашего аккаунта getaimly.io:",
            markup        = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
            parseMarkdown = true,
        )
    }

    fun handleWaitingEmail(chatId: Long, text: String) {
        val session = sessions[chatId] ?: return
        if (!text.contains('@') || !text.contains('.')) {
            sender.sendText(chatId, "⚠️ Некорректный формат email.\n\nПопробуйте ещё раз:")
            return
        }
        session.email = text.lowercase().trim()
        session.step  = BotStep.WAITING_PASSWORD
        sender.sendText(chatId, "🔑 Введите пароль:")
    }

    fun handleWaitingPassword(chatId: Long, text: String, from: User) {
        val session = sessions.remove(chatId) ?: return
        val email   = session.email ?: return

        runCatching {
            authService.login(
                LoginRequest(email = email, password = text),
                ipAddress = "telegram:$chatId",
            )
        }.onSuccess { result ->
            when (result) {
                is LoginResult.Success -> {
                    if (!result.auth.telegramLinked) {
                        authService.linkTelegramDirect(result.auth.userId, from.id, from.userName)
                    }
                    val name = result.auth.firstName ?: email.split("@").first()
                    sender.sendText(chatId, "✅ Добро пожаловать, $name!\n\nТеперь лиды будут приходить сюда.")

                    when (session.pendingAction) {
                        "pay" -> paymentHandler.sendPaymentMessage(chatId, from.id)
                        else  -> showMainMenu(chatId, name)
                    }
                }
                is LoginResult.PendingVerification -> {
                    sender.sendText(
                        chatId,
                        "📧 Email не подтверждён.\n\nПроверьте почту $email и перейдите по ссылке.",
                        markup = keyboard(row(btn("🔄 Войти снова", "auth:retry"), btn("❌ Отмена", "auth:cancel"))),
                    )
                }
            }
        }.onFailure { e ->
            val msg = when (e) {
                is ForbiddenException       -> "🚫 Аккаунт заблокирован."
                is UnauthorizedException    -> "❌ Неверный email или пароль."
                is TooManyRequestsException -> "⏳ ${e.message ?: "Слишком много попыток. Подождите."}"
                is BadRequestException      -> "⚠️ ${e.message ?: "Ошибка запроса."}"
                else                        -> "🔴 Ошибка входа. Попробуйте позже."
            }
            val pendingAction = session.pendingAction
            sender.sendText(
                chatId, msg,
                markup = keyboard(row(
                    btn("🔄 Повторить", if (pendingAction == "pay") "auth:retry_pay" else "auth:retry"),
                    btn("❌ Отмена", "auth:cancel"),
                )),
            )
        }
    }
}