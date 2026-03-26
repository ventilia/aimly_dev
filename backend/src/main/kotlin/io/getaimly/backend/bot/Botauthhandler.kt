package io.getaimly.backend.bot

import io.getaimly.backend.auth.AuthService
import io.getaimly.backend.auth.BadRequestException
import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.auth.LoginResult
import io.getaimly.backend.auth.RegisterViaTelegramResult
import io.getaimly.backend.auth.TooManyRequestsException
import io.getaimly.backend.auth.UnauthorizedException
import io.getaimly.backend.auth.dto.LoginRequest
import io.getaimly.backend.auth.dto.VerifyEmailRequest
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

        // Минимальная длина пароля при регистрации через бота
        private const val MIN_PASSWORD_LENGTH = 8
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
                // Новый пользователь — сохраняем код в сессии, показываем приветствие с кнопками
                pendingReferralCodes[chatId] = refCode

                log.info("[BOT][AUTH] Реферальный код сохранён в сессии: $tgUser code=$refCode")

                val greet = greeting(from.safeName())
                sender.sendText(
                    chatId,
                    "$greet\n\n" +
                            "Вы перешли по реферальной ссылке AIMLY.\n\n" +
                            "AIMLY — сервис поиска лидов в Telegram-чатах по ключевым словам.\n\n" +
                            "🎁 Бонус: зарегистрируйтесь по этой ссылке и получите +7 дней бесплатно.\n\n" +
                            "Войдите в существующий аккаунт или создайте новый прямо здесь:\n\n" +
                            "Для максимального удобства рекомендуем использовать веб-версию:\n" +
                            "🌐 $SITE_URL",
                    markup = keyboard(
                        row(
                            btn("🔐 Войти", "auth:login"),
                            btn("📝 Зарегистрироваться", "auth:register"),
                        ),
                    ),
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
                    "✅ Telegram привязан к аккаунту AIMLY!\n\nТеперь переходим к оплате подписки.",
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
                        "Ссылка привязки устарела.\n\nВойдите в аккаунт или зарегистрируйтесь:",
                        markup = keyboard(
                            row(
                                btn("🔐 Войти",               "auth:login_pay"),
                                btn("📝 Зарегистрироваться",  "auth:register"),
                            ),
                        ),
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
                log.info("[BOT][AUTH] /start?pay: $tgUser не авторизован → показываем кнопки входа/регистрации")
                sender.sendText(
                    chatId,
                    "Для оплаты подписки нужно войти в аккаунт AIMLY:",
                    markup = keyboard(
                        row(
                            btn("🔐 Войти",               "auth:login_pay"),
                            btn("📝 Зарегистрироваться",  "auth:register"),
                        ),
                    ),
                )
            }
            return
        }

        // ── Обычный TG_LINK токен (привязка с сайта) ─────────────────────────
        val linked = authService.linkTelegram(token, from.id, from.userName)
        if (linked) {
            val user = userRepository.findByTelegramId(from.id).orElse(null)
            log.info("[BOT][AUTH] ✅ Telegram привязан через токен: $tgUser userId=${user?.id} email=${user?.email}")
            val name = user?.firstName?.takeIf { it.isNotBlank() } ?: user?.email?.split("@")?.first() ?: "пользователь"
            showMainMenu(chatId, name)
        } else {
            val existing = userRepository.findByTelegramId(from.id).orElse(null)
            if (existing != null) {
                log.info("[BOT][AUTH] Токен устарел, но пользователь уже привязан: $tgUser userId=${existing.id}")
                showMainMenu(chatId, existing.firstName)
            } else {
                log.warn("[BOT][AUTH] ❌ Токен привязки устарел: $tgUser")
                sender.sendText(
                    chatId,
                    "Ссылка устарела.\n\nВойдите в аккаунт или зарегистрируйтесь:",
                    markup = keyboard(
                        row(
                            btn("🔐 Войти",               "auth:login"),
                            btn("📝 Зарегистрироваться",  "auth:register"),
                        ),
                    ),
                )
            }
        }
    }

    // ─── Хранилище referralCode до начала потока входа/регистрации ────────────
    // (живёт только в памяти, достаточно для нашего сценария)
    private val pendingReferralCodes = ConcurrentHashMap<Long, String>()

    /**
     * Стартовый экран для незарегистрированных пользователей.
     * Показывает две кнопки: Войти / Зарегистрироваться.
     */
    fun showWelcome(chatId: Long, name: String?) {
        val greet = greeting(name)
        log.info("[BOT][AUTH] Показ приветствия: chatId=$chatId")
        sender.sendText(
            chatId,
            "$greet\n\n" +
                    "Добро пожаловать в AIMLY — сервис поиска лидов в Telegram-чатах.\n\n" +
                    "Войдите в существующий аккаунт или создайте новый:\n\n" +
                    "Для максимального удобства рекомендуем использовать веб-версию:\n" +
                    "🌐 $SITE_URL",
            markup = keyboard(
                row(
                    btn("🔐 Войти",               "auth:login"),
                    btn("📝 Зарегистрироваться",  "auth:register"),
                ),
            ),
        )
    }

    fun showMainMenu(chatId: Long, name: String?) {
        // В личных чатах chatId == tgUserId, поэтому поиск по chatId корректен
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

        val greet = greeting(user?.firstName)

        sender.editText(
            chatId, msgId,
            "$greet\n\nЧто хотите сделать?",
            markup = keyboard(*rows.toTypedArray()),
        )
    }


    // ─── ВХОД (логин) ─────────────────────────────────────────────────────────

    fun startLoginFlow(chatId: Long, msgId: Int, pendingAction: String? = null) {
        log.info("[BOT][AUTH] Начало входа: chatId=$chatId pendingAction=$pendingAction")
        val existingRefCode = pendingReferralCodes[chatId]
        sessions[chatId] = UserSession(
            step                = BotStep.WAITING_EMAIL,
            msgId               = msgId,
            pendingReferralCode = existingRefCode,
            pendingAction       = pendingAction,
        )
        sender.editText(
            chatId, msgId,
            "🔐 Вход в аккаунт\n\nВведите email от вашего аккаунта getaimly.io:",
            markup        = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
        )
    }

    fun handleWaitingEmail(chatId: Long, text: String) {
        val session = sessions[chatId] ?: return
        val email   = text.lowercase().trim()

        if (!email.contains('@') || !email.contains('.')) {
            log.warn("[BOT][AUTH] Некорректный email при входе: chatId=$chatId input=\"$text\"")
            sender.sendText(chatId, "⚠️ Некорректный формат email.\n\nПопробуйте ещё раз:")
            return
        }

        log.info("[BOT][AUTH] Email введён (вход): chatId=$chatId email=\"$email\"")
        session.email = email
        session.step  = BotStep.WAITING_PASSWORD
        sender.sendText(chatId, "🔑 Введите пароль:")
    }

    fun handleWaitingPassword(chatId: Long, text: String, from: User) {
        val session = sessions[chatId] ?: return
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
                    // Привязываем Telegram если ещё не привязан
                    if (!result.auth.telegramLinked) {
                        authService.linkTelegramDirect(result.auth.userId, from.id, from.userName)
                        log.info("[BOT][AUTH] Telegram привязан после входа: userId=${result.auth.userId} tgId=${from.id}")
                    }
                    val name = result.auth.firstName?.takeIf { it.isNotBlank() } ?: email.split("@").first()
                    log.info("[BOT][AUTH] ✅ Успешный вход: $tgUser userId=${result.auth.userId} email=$email")

                    // Применяем реферальный код если был сохранён
                    val refCode = session.pendingReferralCode ?: pendingReferralCodes.remove(chatId)
                    if (refCode != null) {
                        val user = userRepository.findByTelegramId(from.id).orElse(null)
                        if (user != null) {
                            runCatching { referralService.registerRefereeIfNew(refCode, user) }
                                .onSuccess { log.info("[REFERRAL] Рефери зарегистрирован после входа в боте: userId=${user.id} code=$refCode") }
                                .onFailure { log.warn("[REFERRAL] Ошибка регистрации рефери после входа в боте: ${it.message}") }
                        }
                    }

                    // Сессию удаляем только после успеха
                    sessions.remove(chatId)

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
                    // Email не подтверждён — запрашиваем код из письма
                    // Сохраняем userId в сессии для верификации
                    session.step = BotStep.WAITING_LOGIN_CODE
                    session.pendingVerificationUserId = result.token.let {
                        // Извлекаем userId из токена через userRepository по email
                        userRepository.findByEmail(email).orElse(null)?.id
                    }

                    log.info("[BOT][AUTH] Email не подтверждён, запрашиваем код: $tgUser email=\"$email\"")

                    sender.sendText(
                        chatId,
                        "📧 Подтверждение email\n\n" +
                                "На адрес $email отправлен код подтверждения.\n\n" +
                                "Введите 6-значный код из письма:",
                        markup = keyboard(
                            row(
                                btn("🔄 Выслать код повторно", "auth:resend_code"),
                                btn("❌ Отмена", "auth:cancel"),
                            ),
                        ),
                    )
                }
            }
        }.onFailure { e ->
            val msg = when (e) {
                is TooManyRequestsException -> e.message ?: "Слишком много попыток"
                is UnauthorizedException    -> e.message ?: "Неверные данные"
                is ForbiddenException       -> e.message ?: "Аккаунт заблокирован"
                is BadRequestException      -> e.message ?: "Ошибка запроса"
                else -> {
                    log.error("[BOT][AUTH] Неожиданная ошибка входа: $tgUser email=$email", e)
                    "Произошла ошибка. Попробуйте позже."
                }
            }
            log.warn("[BOT][AUTH] Ошибка входа: $tgUser email=$email причина=${e.message}")
            sessions.remove(chatId)
            sender.sendText(
                chatId,
                "❌ $msg",
                markup = keyboard(
                    row(btn("🔄 Попробовать снова", "auth:login"), btn("❌ Отмена", "auth:cancel_msg")),
                ),
            )
        }
    }

    /**
     * Обработка кода подтверждения email при входе через бота.
     * После успешной верификации привязывает Telegram и открывает главное меню.
     */
    fun handleWaitingLoginCode(chatId: Long, code: String, from: User) {
        val session = sessions[chatId] ?: return
        val userId  = session.pendingVerificationUserId
        val email   = session.email ?: return
        val tgUser  = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        if (userId == null) {
            log.warn("[BOT][AUTH] userId не найден при верификации кода: $tgUser email=$email")
            sessions.remove(chatId)
            sender.sendText(chatId, "❌ Сессия устарела. Начните вход заново:", markup = keyboard(row(btn("🔐 Войти", "auth:login"))))
            return
        }

        val trimmedCode = code.trim()

        if (!trimmedCode.matches(Regex("\\d{6}"))) {
            log.warn("[BOT][AUTH] Некорректный формат кода: $tgUser input=\"${trimmedCode.take(10)}\"")
            sender.sendText(
                chatId,
                "⚠️ Код должен состоять из 6 цифр.\n\nПопробуйте ещё раз:",
                markup = keyboard(
                    row(
                        btn("🔄 Выслать код повторно", "auth:resend_code"),
                        btn("❌ Отмена", "auth:cancel"),
                    ),
                ),
            )
            return
        }

        log.info("[BOT][AUTH] Попытка верификации кода: $tgUser userId=$userId email=$email")

        runCatching {
            authService.verifyEmail(userId, VerifyEmailRequest(code = trimmedCode))
        }.onSuccess { authResponse ->
            // Верификация прошла — привязываем Telegram
            if (!authResponse.telegramLinked) {
                authService.linkTelegramDirect(authResponse.userId, from.id, from.userName)
                log.info("[BOT][AUTH] Telegram привязан после верификации email: userId=$userId tgId=${from.id}")
            }

            val name = authResponse.firstName?.takeIf { it.isNotBlank() } ?: email.split("@").first()
            log.info("[BOT][AUTH] ✅ Email подтверждён, вход выполнен: $tgUser userId=$userId email=$email")

            // Применяем реферальный код если был сохранён
            val refCode = session.pendingReferralCode ?: pendingReferralCodes.remove(chatId)
            if (refCode != null) {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) {
                    runCatching { referralService.registerRefereeIfNew(refCode, user) }
                        .onSuccess { log.info("[REFERRAL] Рефери зарегистрирован после верификации: userId=${user.id} code=$refCode") }
                        .onFailure { log.warn("[REFERRAL] Ошибка регистрации рефери после верификации: ${it.message}") }
                }
            }

            sessions.remove(chatId)

            sender.sendText(chatId, "✅ Email подтверждён! Добро пожаловать, $name!\n\nТеперь лиды будут приходить сюда.")

            when (session.pendingAction) {
                "pay" -> {
                    log.info("[BOT][AUTH] После верификации → переход к оплате: userId=$userId email=$email")
                    paymentHandler.sendPaymentMessage(chatId, from.id)
                }
                else  -> showMainMenu(chatId, name)
            }
        }.onFailure { e ->
            val msg = when (e) {
                is BadRequestException -> e.message ?: "Неверный или истёкший код"
                else -> {
                    log.error("[BOT][AUTH] Ошибка верификации: $tgUser userId=$userId", e)
                    "Произошла ошибка. Попробуйте позже."
                }
            }
            log.warn("[BOT][AUTH] Ошибка верификации кода: $tgUser userId=$userId email=$email причина=${e.message}")
            sender.sendText(
                chatId,
                "❌ $msg\n\nВведите код ещё раз или запросите новый:",
                markup = keyboard(
                    row(
                        btn("🔄 Выслать код повторно", "auth:resend_code"),
                        btn("❌ Отмена", "auth:cancel"),
                    ),
                ),
            )
        }
    }

    /**
     * Повторная отправка кода верификации email (при входе через бота).
     */
    fun resendVerificationCode(chatId: Long, msgId: Int) {
        val session = sessions[chatId]
        if (session == null || session.step != BotStep.WAITING_LOGIN_CODE) {
            log.warn("[BOT][AUTH] Попытка resend без активной сессии верификации: chatId=$chatId")
            sender.editText(chatId, msgId, "Сессия устарела. Начните вход заново:", markup = keyboard(row(btn("🔐 Войти", "auth:login"))))
            return
        }

        val userId = session.pendingVerificationUserId
        val email  = session.email

        if (userId == null || email == null) {
            log.warn("[BOT][AUTH] Нет userId или email для resend: chatId=$chatId")
            sessions.remove(chatId)
            sender.editText(chatId, msgId, "Сессия устарела. Начните вход заново:", markup = keyboard(row(btn("🔐 Войти", "auth:login"))))
            return
        }

        log.info("[BOT][AUTH] Повторная отправка кода верификации: chatId=$chatId userId=$userId email=$email")

        runCatching {
            authService.resendVerificationCode(userId)
        }.onSuccess {
            sender.editText(
                chatId, msgId,
                "📧 Новый код отправлен на $email\n\nВведите 6-значный код из письма:",
                markup = keyboard(
                    row(
                        btn("🔄 Выслать код повторно", "auth:resend_code"),
                        btn("❌ Отмена", "auth:cancel"),
                    ),
                ),
            )
        }.onFailure { e ->
            val msg = when (e) {
                is BadRequestException      -> e.message ?: "Ошибка"
                is TooManyRequestsException -> e.message ?: "Слишком много запросов"
                else -> "Не удалось отправить код. Попробуйте позже."
            }
            log.warn("[BOT][AUTH] Ошибка повторной отправки кода: chatId=$chatId userId=$userId причина=${e.message}")
            sender.editText(
                chatId, msgId,
                "⚠️ $msg\n\nВведите код из предыдущего письма:",
                markup = keyboard(
                    row(
                        btn("🔄 Выслать код повторно", "auth:resend_code"),
                        btn("❌ Отмена", "auth:cancel"),
                    ),
                ),
            )
        }
    }


    // ─── РЕГИСТРАЦИЯ ──────────────────────────────────────────────────────────

    /**
     * Старт потока регистрации.
     * msgId = 0 если сообщение отправляется новым, иначе редактируем существующее.
     */
    fun startRegisterFlow(chatId: Long, msgId: Int) {
        log.info("[BOT][AUTH] Начало регистрации: chatId=$chatId")
        sessions[chatId] = UserSession(
            step                = BotStep.WAITING_REG_EMAIL,
            msgId               = msgId,
            pendingReferralCode = pendingReferralCodes[chatId],
        )
        val text   = "📝 Регистрация\n\nВведите email для нового аккаунта:"
        val markup = keyboard(row(btn("❌ Отмена", "auth:cancel")))
        if (msgId != 0) sender.editText(chatId, msgId, text, markup)
        else            sender.sendText(chatId, text, markup)
    }

    fun handleWaitingRegEmail(chatId: Long, text: String) {
        val session = sessions[chatId] ?: return
        val email   = text.lowercase().trim()

        if (!email.contains('@') || !email.contains('.')) {
            log.warn("[BOT][AUTH] Некорректный email при регистрации: chatId=$chatId input=\"$text\"")
            sender.sendText(chatId, "⚠️ Некорректный формат email.\n\nПопробуйте ещё раз:")
            return
        }

        log.info("[BOT][AUTH] Email для регистрации: chatId=$chatId email=\"$email\"")
        session.regEmail = email
        session.step     = BotStep.WAITING_REG_PASSWORD
        sender.sendText(
            chatId,
            "🔑 Придумайте пароль:\n\n" +
                    "Требования:\n" +
                    "• минимум $MIN_PASSWORD_LENGTH символов\n" +
                    "• строчные и заглавные латинские буквы\n" +
                    "• хотя бы одна цифра",
        )
    }

    fun handleWaitingRegPassword(chatId: Long, text: String) {
        val session  = sessions[chatId] ?: return
        val password = text.trim()

        val validationError = validatePassword(password)
        if (validationError != null) {
            log.warn("[BOT][AUTH] Слабый пароль при регистрации: chatId=$chatId")
            sender.sendText(chatId, "⚠️ $validationError\n\nВведите пароль ещё раз:")
            return
        }

        log.info("[BOT][AUTH] Пароль для регистрации принят: chatId=$chatId")
        session.regPassword = password
        session.step        = BotStep.WAITING_REG_PASSWORD_CONFIRM
        sender.sendText(chatId, "🔑 Повторите пароль:")
    }

    fun handleWaitingRegPasswordConfirm(chatId: Long, text: String, from: User) {
        val session  = sessions.remove(chatId) ?: return
        val email    = session.regEmail    ?: return
        val password = session.regPassword ?: return
        val confirm  = text.trim()
        val tgUser   = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        if (password != confirm) {
            log.warn("[BOT][AUTH] Пароли не совпадают при регистрации: chatId=$chatId")
            // Возвращаем на шаг ввода пароля
            sessions[chatId] = session.copy(step = BotStep.WAITING_REG_PASSWORD, regPassword = null)
            sender.sendText(chatId, "❌ Пароли не совпадают.\n\nВведите пароль заново:")
            return
        }

        val refCode = session.pendingReferralCode ?: pendingReferralCodes.remove(chatId)

        log.info("[BOT][AUTH] Попытка регистрации через бота: $tgUser email=\"$email\"")

        when (val result = authService.registerViaTelegram(
            email            = email,
            password         = password,
            firstName        = from.firstName?.takeIf { it.isNotBlank() },
            telegramId       = from.id,
            telegramUsername = from.userName,
            referralCode     = refCode,
        )) {
            is RegisterViaTelegramResult.Success -> {
                val name = result.user.firstName?.takeIf { it.isNotBlank() } ?: email.split("@").first()
                log.info("[BOT][AUTH] ✅ Регистрация через бота: $tgUser userId=${result.user.id} email=$email")
                sender.sendText(
                    chatId,
                    "✅ Аккаунт создан!\n\n" +
                            "Добро пожаловать, $name!\n\n" +
                            "Вы можете войти на сайте getaimly.io с этим email и паролем.",
                )
                showMainMenu(chatId, name)
            }
            is RegisterViaTelegramResult.EmailTaken -> {
                log.warn("[BOT][AUTH] Email занят при регистрации через бота: $tgUser email=$email")
                sender.sendText(
                    chatId,
                    "❌ Аккаунт с таким email уже существует.\n\nВойдите в него:",
                    markup = keyboard(
                        row(btn("🔐 Войти",               "auth:login"),
                            btn("📝 Другой email",        "auth:register")),
                    ),
                )
            }
            is RegisterViaTelegramResult.TelegramAlreadyLinked -> {
                log.warn("[BOT][AUTH] TG уже привязан при регистрации: $tgUser")
                val linked = userRepository.findByTelegramId(from.id).orElse(null)
                showMainMenu(chatId, linked?.firstName)
            }
        }
    }


    // ─── Вспомогательные ──────────────────────────────────────────────────────

    private fun validatePassword(password: String): String? {
        if (password.length < MIN_PASSWORD_LENGTH)
            return "Пароль должен содержать минимум $MIN_PASSWORD_LENGTH символов."
        if (!password.any { it.isLowerCase() })
            return "Пароль должен содержать строчные буквы."
        if (!password.any { it.isUpperCase() })
            return "Пароль должен содержать заглавные буквы."
        if (!password.any { it.isDigit() })
            return "Пароль должен содержать хотя бы одну цифру."
        return null
    }

    /** Сбросить реферальный код из «ожидающих» при явной отмене */
    fun clearPendingReferral(chatId: Long) {
        pendingReferralCodes.remove(chatId)
    }
}