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
import io.getaimly.backend.referral.ReferralService
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
    private val referralService: ReferralService,
) {

    private val log = LoggerFactory.getLogger(BotAuthHandler::class.java)

    companion object {
        const val SITE_URL       = "https://getaimly.io"
        const val SITE_DASHBOARD = "$SITE_URL/dashboard"
        private const val PAY_PREFIX = "pay_"
        private const val REF_PREFIX = "ref_"
    }

    // ── Хелпер: безопасное имя из Telegram — null если пустое/blank ─────────
    private fun User.safeName(): String? = firstName?.takeIf { it.isNotBlank() }

    // ── Хелпер: приветствие с именем или без ─────────────────────────────────
    private fun greeting(name: String?) = name?.takeIf { it.isNotBlank() }
        ?.let { "👋 Привет, $it!" }
        ?: "👋 Привет!"


    fun handleStart(chatId: Long, from: User, linkToken: String?) {
        val tgUser   = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"
        val existing = userRepository.findByTelegramId(from.id).orElse(null)

        if (linkToken != null) {
            log.info("[BOT][AUTH] /start с токеном: $tgUser token=\"${linkToken.take(20)}…\"")
            handleLinkToken(chatId, from, linkToken)
            return
        }

        if (existing != null) {
            log.info("[BOT][AUTH] /start: $tgUser уже привязан → userId=${existing.id} email=${existing.email} план=${existing.subscriptionPlan} статус=${existing.subscriptionStatus}")
            showMainMenu(chatId, existing.firstName)
        } else {
            log.info("[BOT][AUTH] /start: $tgUser — не авторизован, показываем приветствие")
            showWelcome(chatId, from.safeName())
        }
    }

    private fun handleLinkToken(chatId: Long, from: User, token: String) {
        val tgUser = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        // ── Реферальная ссылка: /start ref_XXXXXX ─────────────────────────────
        if (token.startsWith(REF_PREFIX)) {
            val refCode  = token.removePrefix(REF_PREFIX)
            val existing = userRepository.findByTelegramId(from.id).orElse(null)

            log.info("[BOT][AUTH] Переход по реферальной ссылке: $tgUser code=$refCode")

            val codeEntity = referralService.resolveReferralCode(refCode)
            if (codeEntity == null) {
                log.warn("[BOT][AUTH] Реферальный код не найден: $tgUser code=$refCode")
                if (existing != null) showMainMenu(chatId, existing.firstName)
                else showWelcome(chatId, from.safeName())
                return
            }

            if (existing != null) {
                // Пользователь уже зарегистрирован — пробуем зачесть реферала
                runCatching { referralService.registerRefereeIfNew(refCode, existing) }
                    .onSuccess { log.info("[BOT][AUTH] Рефери зарегистрирован (уже авторизован): userId=${existing.id} code=$refCode") }
                    .onFailure { log.warn("[BOT][AUTH] Ошибка регистрации рефери: ${it.message}") }
                showMainMenu(chatId, existing.firstName)
            } else {
                // Новый пользователь — сохраняем код в сессии, показываем приветствие
                sessions[chatId] = UserSession(
                    step                = BotStep.WAITING_EMAIL,
                    msgId               = 0,
                    pendingReferralCode = refCode,
                )
                log.info("[BOT][AUTH] Реферальный код сохранён в сессии: $tgUser code=$refCode")

                // ✅ Фикс: используем safeName() чтобы не было "Привет, .!"
                val greet = greeting(from.safeName())
                sender.sendText(
                    chatId,
                    "$greet\n\n" +
                            "Вы перешли по реферальной ссылке AIMLY\\.\n\n" +
                            "*AIMLY* — сервис поиска лидов в Telegram\\-чатах по ключевым словам\\.\n\n" +
                            "🎁 *Бонус:* зарегистрируйтесь по этой ссылке и получите +7 дней бесплатно\\. " +
                            "Войдите или зарегистрируйтесь на сайте, а затем авторизуйтесь здесь:\n" +
                            "🌐 $SITE_URL\n\n" +
                            "Введите email от аккаунта AIMLY:",
                    markup        = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
                    parseMarkdown = true,
                )
            }
            return
        }

        // ── pay_ токен ────────────────────────────────────────────────────────
        if (token.startsWith(PAY_PREFIX)) {
            val realToken = token.removePrefix(PAY_PREFIX)
            log.info("[BOT][AUTH] Попытка привязки через pay_token: $tgUser")
            val linked = authService.linkTelegram(realToken, from.id, from.userName)

            if (linked) {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                log.info("[BOT][AUTH] ✅ Привязка через pay_token успешна: $tgUser userId=${user?.id} email=${user?.email}")
                sender.sendText(
                    chatId,
                    "✅ *Telegram привязан к аккаунту AIMLY\\!*\n\n" +
                            "Теперь переходим к оплате подписки\\.",
                    parseMarkdown = true,
                )
                paymentHandler.sendPaymentMessage(chatId, from.id)
            } else {
                val existing = userRepository.findByTelegramId(from.id).orElse(null)
                if (existing != null) {
                    log.info("[BOT][AUTH] pay_token устарел, но пользователь уже привязан: $tgUser userId=${existing.id} email=${existing.email}")
                    sender.sendText(chatId, "Ссылка привязки устарела, но вы уже авторизованы. Вот кнопка оплаты:")
                    paymentHandler.sendPaymentMessage(chatId, from.id)
                } else {
                    log.warn("[BOT][AUTH] ❌ pay_token устарел, пользователь не найден: $tgUser")
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

        // ── pay (без токена) ──────────────────────────────────────────────────
        if (token == "pay") {
            val existing = userRepository.findByTelegramId(from.id).orElse(null)
            if (existing != null) {
                log.info("[BOT][AUTH] /start?pay: $tgUser уже привязан → переход к оплате userId=${existing.id} email=${existing.email}")
                paymentHandler.sendPaymentMessage(chatId, from.id)
            } else {
                log.info("[BOT][AUTH] /start?pay: $tgUser не авторизован → запрашиваем email для входа перед оплатой")
                sessions[chatId] = UserSession(
                    step          = BotStep.WAITING_EMAIL,
                    msgId         = 0,
                    pendingAction = "pay",
                )
                sender.sendText(
                    chatId,
                    "Для оплаты подписки войдите в аккаунт AIMLY.\n\nВведите email:",
                    markup = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
                )
            }
            return
        }

        // ── Обычная привязка через link token ────────────────────────────────
        log.info("[BOT][AUTH] Попытка привязки Telegram: $tgUser")
        val ok = authService.linkTelegram(token, from.id, from.userName)
        if (ok) {
            val user = userRepository.findByTelegramId(from.id).orElse(null)
            log.info("[BOT][AUTH] ✅ Telegram успешно привязан: $tgUser userId=${user?.id} email=${user?.email}")
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
            showMainMenu(chatId, user?.firstName)
        } else {
            log.warn("[BOT][AUTH] ❌ Недействительный link token: $tgUser")
            sender.sendText(
                chatId,
                "❌ Ссылка недействительна или истекла.\n\n" +
                        "Запросите новую ссылку в личном кабинете:\n" +
                        "🌐 $SITE_DASHBOARD → Профиль → Привязать Telegram",
            )
        }
    }

    fun showWelcome(chatId: Long, name: String?) {
        log.info("[BOT][AUTH] Показываем приветствие: chatId=$chatId firstName=${name ?: "—"}")
        // ✅ Фикс: greeting() защищает от пустой строки
        val greet = greeting(name)
        sender.sendText(
            chatId,
            "$greet\n\n" +
                    "*AIMLY* — сервис поиска лидов в Telegram\\-чатах по ключевым словам\\.\n\n" +
                    "🤖 Этот бот повторяет весь функционал сайта \\(кроме администрирования\\)\\.\n" +
                    "Для максимального удобства рекомендуем использовать веб\\-версию:\n" +
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

        // ✅ Фикс: greeting() защищает от пустой строки
        val greet = greeting(name)

        sender.sendText(
            chatId,
            "$greet\n\nЧто хотите сделать?",
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

        // ✅ Фикс: берём имя из БД, защищаем от пустой строки
        val greet = greeting(user?.firstName)

        sender.editText(
            chatId, msgId,
            "$greet\n\nЧто хотите сделать?",
            markup = keyboard(*rows.toTypedArray()),
        )
    }


    fun startLoginFlow(chatId: Long, msgId: Int) {
        log.info("[BOT][AUTH] Начало входа (форма логина): chatId=$chatId")
        val existingRefCode = sessions[chatId]?.pendingReferralCode
        sessions[chatId] = UserSession(
            step                = BotStep.WAITING_EMAIL,
            msgId               = msgId,
            pendingReferralCode = existingRefCode,
        )
        sender.editText(
            chatId, msgId,
            "🔐 *Вход в аккаунт*\n\nВведите email от вашего аккаунта getaimly\\.io:",
            markup        = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
            parseMarkdown = true,
        )
    }

    fun handleWaitingEmail(chatId: Long, text: String) {
        val session = sessions[chatId] ?: return
        val email   = text.lowercase().trim()

        if (!email.contains('@') || !email.contains('.')) {
            log.warn("[BOT][AUTH] Некорректный email: chatId=$chatId input=\"$text\"")
            sender.sendText(chatId, "⚠️ Некорректный формат email.\n\nПопробуйте ещё раз:")
            return
        }

        log.info("[BOT][AUTH] Email введён: chatId=$chatId email=\"$email\"")
        session.email = email
        session.step  = BotStep.WAITING_PASSWORD
        sender.sendText(chatId, "🔑 Введите пароль:")
    }

    fun handleWaitingPassword(chatId: Long, text: String, from: User) {
        val session = sessions.remove(chatId) ?: return
        val email   = session.email ?: return
        val tgUser  = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        log.info("[BOT][AUTH] Попытка входа: $tgUser email=\"$email\"")

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
                    val name = result.auth.firstName?.takeIf { it.isNotBlank() } ?: email.split("@").first()
                    log.info("[BOT][AUTH] ✅ Успешный вход: $tgUser userId=${result.auth.userId} email=$email план=${result.auth.subscriptionPlan} статус=${result.auth.subscriptionStatus}")

                    // ── Применяем реферальный код если был сохранён ──────────
                    val refCode = session.pendingReferralCode
                    if (refCode != null) {
                        val user = userRepository.findByTelegramId(from.id).orElse(null)
                        if (user != null) {
                            runCatching { referralService.registerRefereeIfNew(refCode, user) }
                                .onSuccess { log.info("[REFERRAL] Рефери зарегистрирован после входа в боте: userId=${user.id} code=$refCode") }
                                .onFailure { log.warn("[REFERRAL] Ошибка регистрации рефери после входа в боте: ${it.message}") }
                        }
                    }
                    // ─────────────────────────────────────────────────────────

                    sender.sendText(chatId, "✅ Добро пожаловать, $name!\n\nТеперь лиды будут приходить сюда.")

                    when (session.pendingAction) {
                        "pay" -> {
                            log.info("[BOT][AUTH] После входа → переход к оплате: userId=${result.auth.userId} email=$email")
                            paymentHandler.sendPaymentMessage(chatId, from.id)
                        }
                        else  -> showMainMenu(chatId, name)
                    }
                }
                is LoginResult.PendingVerification -> {
                    log.warn("[BOT][AUTH] Email не подтверждён: $tgUser email=\"$email\"")
                    sessions[chatId] = session.copy(step = BotStep.WAITING_EMAIL, email = null)
                    sender.sendText(
                        chatId,
                        "📧 Email не подтверждён.\n\nПроверьте почту $email и перейдите по ссылке.",
                        markup = keyboard(row(btn("🔄 Войти снова", "auth:retry"), btn("❌ Отмена", "auth:cancel"))),
                    )
                }
            }
        }.onFailure { e ->
            val msg = when (e) {
                is TooManyRequestsException -> e.message ?: "Слишком много попыток"
                is UnauthorizedException    -> e.message ?: "Неверный email или пароль"
                is ForbiddenException       -> e.message ?: "Аккаунт заблокирован"
                is BadRequestException      -> e.message ?: "Ошибка запроса"
                else -> {
                    log.error("[BOT][AUTH] Неожиданная ошибка входа: $tgUser email=$email", e)
                    "Произошла ошибка. Попробуйте позже."
                }
            }
            log.warn("[BOT][AUTH] Ошибка входа: $tgUser email=$email причина=${e.message}")
            sessions[chatId] = session.copy(step = BotStep.WAITING_EMAIL, email = null)
            sender.sendText(
                chatId,
                "❌ $msg\n\nВведите email ещё раз или нажмите Отмена:",
                markup = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
            )
        }
    }
}