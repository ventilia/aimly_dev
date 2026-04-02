package io.getaimly.backend.bot

import io.getaimly.backend.auth.AuthService
import io.getaimly.backend.auth.BadRequestException
import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.auth.LoginResult
import io.getaimly.backend.auth.RegisterViaTelegramResult
import io.getaimly.backend.auth.TooManyRequestsException
import io.getaimly.backend.auth.UnauthorizedException
import io.getaimly.backend.auth.VerifyViaTelegramResult
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
        private const val MIN_PASSWORD_LENGTH = 8
    }

    private fun User.safeName(): String? = firstName?.takeIf { it.isNotBlank() }
    private fun greeting(name: String?) = name?.takeIf { it.isNotBlank() }
        ?.let { "👋 Привет, $it!" }
        ?: "👋 Привет!"

    // Реферальные коды ожидающих регистрацию/вход пользователей (до верификации)
    private val pendingReferralCodes = ConcurrentHashMap<Long, String>()


    // ─── /start ───────────────────────────────────────────────────────────────────

    fun handleStart(chatId: Long, from: User, linkToken: String?) {
        val tgUser   = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"
        val existing = userRepository.findByTelegramId(from.id).orElse(null)

        if (linkToken != null) {
            log.info("[BOT][AUTH] /start с токеном: $tgUser token=\"${linkToken.take(20)}…\"")
            handleLinkToken(chatId, from, linkToken)
            return
        }

        if (existing != null) {
            if (!existing.emailVerified) {
                log.info("[BOT][AUTH] /start: $tgUser найден, но email не подтверждён → userId=${existing.id} email=${existing.email}")
                sessions[chatId] = UserSession(
                    step                      = BotStep.WAITING_LOGIN_CODE,
                    email                     = existing.email,
                    pendingVerificationUserId = existing.id,
                )
                sender.sendText(
                    chatId,
                    "📧 *Подтверждение email*\n\n" +
                            "Для завершения регистрации введите 6-значный код из письма,\n" +
                            "отправленного на ${existing.email}.",
                    markup = keyboard(
                        row(
                            btn("🔄 Выслать код повторно", "auth:resend_code"),
                            btn("❌ Отмена", "auth:cancel"),
                        ),
                    ),
                    parseMarkdown = true,
                )
                return
            }
            log.info("[BOT][AUTH] /start: $tgUser уже привязан → userId=${existing.id} email=${existing.email} план=${existing.subscriptionPlan} статус=${existing.subscriptionStatus}")
            showMainMenu(chatId, existing.firstName, from.id)
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
                if (existing != null) showMainMenu(chatId, existing.firstName, from.id)
                else showWelcome(chatId, from.safeName())
                return
            }

            if (existing != null) {
                runCatching { referralService.registerRefereeIfNew(refCode, existing) }
                    .onSuccess { log.info("[BOT][AUTH] Рефери зарегистрирован (уже авторизован): userId=${existing.id} code=$refCode") }
                    .onFailure { log.warn("[BOT][AUTH] Ошибка регистрации рефери: ${it.message}") }
                showMainMenu(chatId, existing.firstName, from.id)
            } else {
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
                sender.sendText(chatId, "✅ Telegram привязан к аккаунту AIMLY!\n\nТеперь переходим к оплате подписки.")
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
                                btn("🔐 Войти",              "auth:login_pay"),
                                btn("📝 Зарегистрироваться", "auth:register"),
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
                            btn("🔐 Войти",              "auth:login_pay"),
                            btn("📝 Зарегистрироваться", "auth:register"),
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
            showMainMenu(chatId, name, from.id)
        } else {
            val existing = userRepository.findByTelegramId(from.id).orElse(null)
            if (existing != null) {
                log.info("[BOT][AUTH] Токен устарел, но пользователь уже привязан: $tgUser userId=${existing.id}")
                showMainMenu(chatId, existing.firstName, from.id)
            } else {
                log.warn("[BOT][AUTH] ❌ Токен привязки устарел: $tgUser")
                sender.sendText(
                    chatId,
                    "Ссылка устарела.\n\nВойдите в аккаунт или зарегистрируйтесь:",
                    markup = keyboard(
                        row(
                            btn("🔐 Войти",              "auth:login"),
                            btn("📝 Зарегистрироваться", "auth:register"),
                        ),
                    ),
                )
            }
        }
    }


    // ─── Меню ─────────────────────────────────────────────────────────────────────

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
                    btn("🔐 Войти",              "auth:login"),
                    btn("📝 Зарегистрироваться", "auth:register"),
                ),
            ),
        )
    }

    fun showMainMenu(chatId: Long, name: String?, tgUserId: Long = chatId) {
        val user       = userRepository.findByTelegramId(tgUserId).orElse(null)
        val newCount   = user?.let { leadRepository.countByUserIdAndStatus(it.id, LeadStatus.NEW) } ?: 0
        val leadsLabel = if (newCount > 0) "📬 Лиды  •  $newCount новых" else "📋 Лиды"

        val rows = mutableListOf(
            row(btn(leadsLabel,         "menu:leads")),
            row(btn("💬 Чаты",          "menu:chats"),
                btn("🔍 Ключевые слова", "menu:keywords")),
            row(btn("👤 Профиль",        "menu:profile"),
                btn("❓ Помощь",         "menu:help")),
        )

        if (user?.subscriptionStatus != "ACTIVE") {
            rows.add(row(btn("💳 Оплатить подписку", "payment:plans")))
        }

        sender.sendText(
            chatId,
            "${greeting(name)}\n\nЧто хотите сделать?",
            markup = keyboard(*rows.toTypedArray()),
        )
    }

   fun showMainMenu(chatId: Long, name: String?, tgUserId: Long = chatId) {
       val user       = userRepository.findByTelegramId(tgUserId).orElse(null)
       val newCount   = user?.let { leadRepository.countByUserIdAndStatus(it.id, LeadStatus.NEW) } ?: 0
       val leadsLabel = if (newCount > 0) "📬 Лиды  •  $newCount новых" else "📋 Лиды"

       val rows = mutableListOf(
           row(btn(leadsLabel,         "menu:leads")),
           row(btn("💬 Чаты",          "menu:chats"),
               btn("🔍 Ключевые слова", "menu:keywords")),
           row(btn("📤 Анализ экспорта чата", "export:start")),
           row(btn("👤 Профиль",        "menu:profile"),
               btn("❓ Помощь",         "menu:help")),
       )

       if (user?.subscriptionStatus != "ACTIVE") {
           rows.add(row(btn("💳 Оплатить подписку", "payment:plans")))
       }

       sender.sendText(
           chatId,
           "${greeting(name)}\n\nЧто хотите сделать?",
           markup = keyboard(*rows.toTypedArray()),
       )
   }


    // ─── ВХОД (логин) ─────────────────────────────────────────────────────────────

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
            markup = keyboard(
                row(
                    btn("🔑 Забыл пароль", "auth:forgot_password"),
                    btn("❌ Отмена",        "auth:cancel"),
                ),
            ),
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

        // ── Проверяем наличие пароля до перехода на шаг WAITING_PASSWORD ──────
        // Если аккаунт создан через Google OAuth — пароля нет. Предлагаем два
        // варианта: войти по одноразовому коду на email или привязать через веб.
        val user = userRepository.findByEmail(email).orElse(null)
        if (user != null && user.password.isNullOrBlank()) {
            log.info("[BOT][AUTH] OAuth-аккаунт без пароля при входе: chatId=$chatId email=\"$email\" userId=${user.id}")

            // Отправляем код заранее, чтобы пользователь не ждал после нажатия кнопки
            runCatching { authService.resendVerificationCode(user.id) }.onFailure {
                // Если код уже свежий — ничего страшного, он просто получит старый
                log.debug("[BOT][AUTH] Код уже отправлен или ошибка: ${it.message}")
            }

            session.pendingVerificationUserId = user.id
            // Остаёмся на шаге WAITING_EMAIL — шаг пароля пропускаем полностью.
            // При нажатии кнопки «Войти по коду» переведём в WAITING_LOGIN_CODE.

            sender.sendText(
                chatId,
                "🔑 Ваш аккаунт создан через Google\n\n" +
                        "Пароль для входа через бота не задан.\n\n" +
                        "Выберите способ входа:\n\n" +
                        "📧 *Войти по коду* — получите одноразовый код на $email\n\n" +
                        "🌐 *Привязать через сайт* — войдите на $SITE_URL через Google, " +
                        "затем в Профиле нажмите «Привязать Telegram»",
                markup = keyboard(
                    row(btn("📧 Войти по коду на email", "auth:login_by_code")),
                    row(btn("❌ Отмена", "auth:cancel_msg")),
                ),
                parseMarkdown = true,
            )
            return
        }

        session.step = BotStep.WAITING_PASSWORD
        sender.sendText(
            chatId,
            "🔑 Введите пароль:",
            markup = keyboard(row(btn("🔑 Забыл пароль", "auth:forgot_password"))),
        )
    }

    /**
     * Пользователь нажал «Войти по коду на email».
     * Переводим сессию в шаг WAITING_LOGIN_CODE и просим ввести код.
     */
    fun startLoginByCodeFlow(chatId: Long, msgId: Int) {
        val session = sessions[chatId]
        val email   = session?.email
        val userId  = session?.pendingVerificationUserId

        if (session == null || email == null || userId == null) {
            log.warn("[BOT][AUTH] startLoginByCodeFlow: сессия устарела chatId=$chatId")
            sender.editText(
                chatId, msgId,
                "Сессия устарела. Начните вход заново:",
                markup = keyboard(row(btn("🔐 Войти", "auth:login"))),
            )
            return
        }

        log.info("[BOT][AUTH] Вход по коду на email: chatId=$chatId email=\"$email\" userId=$userId")

        // Отправляем свежий код
        runCatching { authService.resendVerificationCode(userId) }.onFailure {
            log.debug("[BOT][AUTH] Код уже свежий или ошибка resend: ${it.message}")
        }

        session.step = BotStep.WAITING_LOGIN_CODE

        sender.editText(
            chatId, msgId,
            "📧 *Код отправлен*\n\n" +
                    "На адрес $email отправлен 6-значный код для входа.\n\n" +
                    "Введите код из письма:",
            markup = keyboard(
                row(
                    btn("🔄 Выслать код повторно", "auth:resend_code"),
                    btn("❌ Отмена", "auth:cancel"),
                ),
            ),
            parseMarkdown = true,
        )
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
                    if (!result.auth.telegramLinked) {
                        authService.linkTelegramDirect(result.auth.userId, from.id, from.userName)
                        log.info("[BOT][AUTH] Telegram привязан после входа: userId=${result.auth.userId} tgId=${from.id}")
                    }
                    val name = result.auth.firstName?.takeIf { it.isNotBlank() } ?: email.split("@").first()
                    log.info("[BOT][AUTH] ✅ Успешный вход: $tgUser userId=${result.auth.userId} email=$email")

                    val refCode = session.pendingReferralCode ?: pendingReferralCodes.remove(chatId)
                    if (refCode != null) {
                        val user = userRepository.findByTelegramId(from.id).orElse(null)
                        if (user != null) {
                            runCatching { referralService.registerRefereeIfNew(refCode, user) }
                                .onSuccess { log.info("[REFERRAL] Рефери зарегистрирован после входа в боте: userId=${user.id} code=$refCode") }
                                .onFailure { log.warn("[REFERRAL] Ошибка регистрации рефери после входа в боте: ${it.message}") }
                        }
                    }

                    sessions.remove(chatId)
                    sender.sendText(chatId, "✅ Добро пожаловать, $name!\n\nТеперь лиды будут приходить сюда.")

                    when (session.pendingAction) {
                        "pay" -> {
                            log.info("[BOT][AUTH] После входа → переход к оплате: userId=${result.auth.userId} email=$email")
                            paymentHandler.sendPaymentMessage(chatId, from.id)
                        }
                        else -> showMainMenu(chatId, name, from.id)
                    }
                }

                is LoginResult.PendingVerification -> {
                    session.step                      = BotStep.WAITING_LOGIN_CODE
                    session.pendingVerificationUserId = userRepository.findByEmail(email).orElse(null)?.id

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

                // ── Race condition: между handleWaitingEmail и handleWaitingPassword
                // пользователь успел удалить пароль (крайне маловероятно, но обрабатываем).
                is LoginResult.NoPassword -> {
                    log.warn("[BOT][AUTH] NoPassword в handleWaitingPassword (race condition): $tgUser email=$email userId=${result.userId}")

                    runCatching { authService.resendVerificationCode(result.userId) }.onFailure {
                        log.debug("[BOT][AUTH] Код уже свежий: ${it.message}")
                    }

                    session.step                      = BotStep.WAITING_LOGIN_CODE
                    session.pendingVerificationUserId = result.userId

                    sender.sendText(
                        chatId,
                        "🔑 Ваш аккаунт создан через Google\n\n" +
                                "Пароль не задан. На $email отправлен одноразовый код для входа.\n\n" +
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
                    row(btn("🔑 Забыл пароль", "auth:forgot_password")),
                ),
            )
        }
    }

    fun handleWaitingLoginCode(chatId: Long, code: String, from: User) {
        val session = sessions[chatId] ?: return
        val userId  = session.pendingVerificationUserId
        val email   = session.email ?: return
        val tgUser  = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        if (userId == null) {
            log.warn("[BOT][AUTH] userId не найден при верификации кода: $tgUser email=$email")
            sessions.remove(chatId)
            sender.sendText(
                chatId,
                "❌ Сессия устарела. Начните вход заново:",
                markup = keyboard(row(btn("🔐 Войти", "auth:login"))),
            )
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

        val userEntity = userRepository.findById(userId).orElse(null)
        val isNewRegistration = userEntity != null && !userEntity.emailVerified

        if (isNewRegistration) {
            handleRegistrationVerification(chatId, userId, trimmedCode, session, from, tgUser, email)
        } else {
            handleLoginVerification(chatId, userId, trimmedCode, session, from, tgUser, email)
        }
    }

    private fun handleRegistrationVerification(
        chatId:  Long,
        userId:  Long,
        code:    String,
        session: UserSession,
        from:    User,
        tgUser:  String,
        email:   String,
    ) {
        val refCode = session.pendingReferralCode ?: pendingReferralCodes.remove(chatId)

        val result = authService.verifyEmailViaTelegram(
            userId       = userId,
            code         = code,
            referralCode = refCode,
        )

        when (result) {
            is VerifyViaTelegramResult.Success,
            is VerifyViaTelegramResult.AlreadyVerified -> {
                val user = when (result) {
                    is VerifyViaTelegramResult.Success         -> result.user
                    is VerifyViaTelegramResult.AlreadyVerified -> result.user
                    else -> return
                }
                val name = user.firstName?.takeIf { it.isNotBlank() } ?: email.split("@").first()
                log.info("[BOT][AUTH] ✅ Регистрация завершена, email подтверждён: $tgUser userId=$userId email=$email")

                sessions.remove(chatId)
                pendingReferralCodes.remove(chatId)

                sender.sendText(
                    chatId,
                    "✅ Email подтверждён!\n\n" +
                            "Добро пожаловать, $name! Аккаунт активирован.\n\n" +
                            "Вы можете войти на сайте getaimly.io с этим email и паролем.",
                )

                when (session.pendingAction) {
                    "pay" -> {
                        log.info("[BOT][AUTH] После регистрации → переход к оплате: userId=$userId email=$email")
                        paymentHandler.sendPaymentMessage(chatId, from.id)
                    }
                    else -> showMainMenu(chatId, name, from.id)
                }
            }

            is VerifyViaTelegramResult.InvalidCode -> {
                log.warn("[BOT][AUTH] Неверный код при регистрации: $tgUser userId=$userId email=$email")
                sender.sendText(
                    chatId,
                    "❌ Неверный код.\n\nПроверьте письмо и введите код ещё раз:",
                    markup = keyboard(
                        row(
                            btn("🔄 Выслать код повторно", "auth:resend_code"),
                            btn("❌ Отмена", "auth:cancel"),
                        ),
                    ),
                )
            }

            is VerifyViaTelegramResult.ExpiredCode -> {
                log.info("[BOT][AUTH] Код истёк при регистрации: $tgUser userId=$userId email=$email")
                sender.sendText(
                    chatId,
                    "⏱ Код истёк.\n\nЗапросите новый:",
                    markup = keyboard(
                        row(
                            btn("🔄 Выслать код повторно", "auth:resend_code"),
                            btn("❌ Отмена", "auth:cancel"),
                        ),
                    ),
                )
            }

            is VerifyViaTelegramResult.UserNotFound -> {
                log.error("[BOT][AUTH] Пользователь не найден при верификации регистрации: $tgUser userId=$userId")
                sessions.remove(chatId)
                sender.sendText(
                    chatId,
                    "❌ Произошла ошибка. Попробуйте зарегистрироваться заново:",
                    markup = keyboard(row(btn("📝 Зарегистрироваться", "auth:register"))),
                )
            }
        }
    }

    private fun handleLoginVerification(
        chatId:  Long,
        userId:  Long,
        code:    String,
        session: UserSession,
        from:    User,
        tgUser:  String,
        email:   String,
    ) {
        runCatching {
            authService.verifyEmail(userId, VerifyEmailRequest(code = code))
        }.onSuccess { authResponse ->
            if (!authResponse.telegramLinked) {
                authService.linkTelegramDirect(authResponse.userId, from.id, from.userName)
                log.info("[BOT][AUTH] Telegram привязан после верификации email: userId=$userId tgId=${from.id}")
            }

            val name = authResponse.firstName?.takeIf { it.isNotBlank() } ?: email.split("@").first()
            log.info("[BOT][AUTH] ✅ Email подтверждён, вход выполнен: $tgUser userId=$userId email=$email")

            val refCode = session.pendingReferralCode ?: pendingReferralCodes.remove(chatId)
            if (refCode != null) {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) {
                    runCatching { referralService.registerRefereeIfNew(refCode, user) }
                        .onSuccess { log.info("[REFERRAL] Рефери зарегистрирован после верификации входа: userId=${user.id} code=$refCode") }
                        .onFailure { log.warn("[REFERRAL] Ошибка регистрации рефери после верификации входа: ${it.message}") }
                }
            }

            sessions.remove(chatId)
            sender.sendText(chatId, "✅ Вход выполнен! Добро пожаловать, $name!\n\nТеперь лиды будут приходить сюда.")

            when (session.pendingAction) {
                "pay" -> {
                    log.info("[BOT][AUTH] После верификации входа → переход к оплате: userId=$userId email=$email")
                    paymentHandler.sendPaymentMessage(chatId, from.id)
                }
                else -> showMainMenu(chatId, name, from.id)
            }
        }.onFailure { e ->
            val msg = when (e) {
                is BadRequestException -> e.message ?: "Неверный или истёкший код"
                else -> {
                    log.error("[BOT][AUTH] Ошибка верификации входа: $tgUser userId=$userId", e)
                    "Произошла ошибка. Попробуйте позже."
                }
            }
            log.warn("[BOT][AUTH] Ошибка верификации кода (вход): $tgUser userId=$userId email=$email причина=${e.message}")
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

    fun resendVerificationCode(chatId: Long, msgId: Int) {
        val session = sessions[chatId]
        if (session == null || session.step != BotStep.WAITING_LOGIN_CODE) {
            log.warn("[BOT][AUTH] Попытка resend без активной сессии верификации: chatId=$chatId")
            sender.editText(
                chatId, msgId,
                "Сессия устарела. Начните вход заново:",
                markup = keyboard(row(btn("🔐 Войти", "auth:login"))),
            )
            return
        }

        val userId = session.pendingVerificationUserId
        val email  = session.email

        if (userId == null || email == null) {
            log.warn("[BOT][AUTH] Нет userId или email для resend: chatId=$chatId")
            sessions.remove(chatId)
            sender.editText(
                chatId, msgId,
                "Сессия устарела. Начните вход заново:",
                markup = keyboard(row(btn("🔐 Войти", "auth:login"))),
            )
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


    // ─── РЕГИСТРАЦИЯ ──────────────────────────────────────────────────────────────

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
        val session  = sessions[chatId] ?: return
        val email    = session.regEmail    ?: return
        val password = session.regPassword ?: return
        val confirm  = text.trim()
        val tgUser   = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        if (password != confirm) {
            log.warn("[BOT][AUTH] Пароли не совпадают при регистрации: chatId=$chatId")
            sessions[chatId] = session.copy(step = BotStep.WAITING_REG_PASSWORD, regPassword = null)
            sender.sendText(chatId, "❌ Пароли не совпадают.\n\nВведите пароль заново:")
            return
        }

        val refCode = session.pendingReferralCode ?: pendingReferralCodes[chatId]

        log.info("[BOT][AUTH] Попытка регистрации через бота: $tgUser email=\"$email\"")

        when (val result = authService.registerViaTelegram(
            email            = email,
            password         = password,
            firstName        = from.firstName?.takeIf { it.isNotBlank() },
            telegramId       = from.id,
            telegramUsername = from.userName,
            referralCode     = refCode,
        )) {
            is RegisterViaTelegramResult.PendingEmailVerification -> {
                sessions[chatId] = session.copy(
                    step                      = BotStep.WAITING_LOGIN_CODE,
                    email                     = result.email,
                    pendingVerificationUserId = result.userId,
                )
                log.info("[BOT][AUTH] Регистрация: ожидаем подтверждения email: $tgUser userId=${result.userId} email=${result.email}")
                sender.sendText(
                    chatId,
                    "📧 *Подтверждение email*\n\n" +
                            "Аккаунт создан! На адрес ${result.email} отправлен код подтверждения.\n\n" +
                            "Введите 6-значный код из письма:",
                    markup = keyboard(
                        row(
                            btn("🔄 Выслать код повторно", "auth:resend_code"),
                            btn("❌ Отмена", "auth:cancel"),
                        ),
                    ),
                    parseMarkdown = true,
                )
            }

            is RegisterViaTelegramResult.Success -> {
                val name = result.user.firstName?.takeIf { it.isNotBlank() } ?: email.split("@").first()
                log.info("[BOT][AUTH] ✅ Регистрация через бота (без верификации): $tgUser userId=${result.user.id} email=$email")
                sessions.remove(chatId)
                pendingReferralCodes.remove(chatId)
                sender.sendText(
                    chatId,
                    "✅ Аккаунт создан!\n\nДобро пожаловать, $name!\n\n" +
                            "Вы можете войти на сайте getaimly.io с этим email и паролем.",
                )
                showMainMenu(chatId, name, from.id)
            }

            is RegisterViaTelegramResult.EmailTaken -> {
                log.warn("[BOT][AUTH] Email занят при регистрации через бота: $tgUser email=$email")
                sessions.remove(chatId)
                sender.sendText(
                    chatId,
                    "❌ Аккаунт с таким email уже существует.\n\nВойдите в него:",
                    markup = keyboard(
                        row(
                            btn("🔐 Войти",          "auth:login"),
                            btn("📝 Другой email",   "auth:register"),
                        ),
                    ),
                )
            }

            is RegisterViaTelegramResult.TelegramAlreadyLinked -> {
                log.warn("[BOT][AUTH] TG уже привязан при регистрации: $tgUser")
                sessions.remove(chatId)
                val linked = userRepository.findByTelegramId(from.id).orElse(null)
                showMainMenu(chatId, linked?.firstName, from.id)
            }
        }
    }


    // ─── СБРОС ПАРОЛЯ ─────────────────────────────────────────────────────────────

    /**
     * Начало flow сброса пароля. Запрашивает email.
     * Вызывается по кнопке «Забыл пароль» из экрана входа.
     */
    fun startForgotPasswordFlow(chatId: Long, msgId: Int) {
        log.info("[BOT][AUTH] Начало сброса пароля: chatId=$chatId")
        sessions[chatId] = UserSession(
            step  = BotStep.WAITING_RESET_EMAIL,
            msgId = msgId,
        )
        sender.editText(
            chatId, msgId,
            "🔑 Сброс пароля\n\nВведите email от вашего аккаунта:",
            markup = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
        )
    }

    /**
     * Получили email для сброса пароля — вызываем requestPasswordReset,
     * переходим к ожиданию кода.
     */
    fun handleWaitingResetEmail(chatId: Long, text: String) {
        val session = sessions[chatId] ?: return
        val email   = text.lowercase().trim()

        if (!email.contains('@') || !email.contains('.')) {
            sender.sendText(chatId, "⚠️ Некорректный формат email.\n\nПопробуйте ещё раз:")
            return
        }

        log.info("[BOT][AUTH] Email для сброса пароля: chatId=$chatId email=\"$email\"")

        // Отправляем код всегда с одним и тем же ответом (чтобы не раскрывать наличие email в БД)
        runCatching {
            authService.requestPasswordReset(email)
        }.onFailure { e ->
            if (e is TooManyRequestsException) {
                sender.sendText(chatId, "⚠️ ${e.message}")
                return
            }
            log.warn("[BOT][AUTH] Ошибка при запросе сброса пароля: chatId=$chatId причина=${e.message}")
        }

        session.resetEmail = email
        session.step       = BotStep.WAITING_RESET_CODE

        sender.sendText(
            chatId,
            "📧 Если этот email зарегистрирован в AIMLY, на него отправлен 6-значный код.\n\n" +
                    "Введите код из письма (действителен 15 минут):",
            markup = keyboard(
                row(
                    btn("🔄 Выслать код повторно", "auth:resend_reset_code"),
                    btn("❌ Отмена", "auth:cancel"),
                ),
            ),
        )
    }

    /**
     * Получили код из письма — сохраняем, переходим к вводу нового пароля.
     */
    fun handleWaitingResetCode(chatId: Long, code: String) {
        val session = sessions[chatId] ?: return
        val trimmed = code.trim()

        if (!trimmed.matches(Regex("\\d{6}"))) {
            sender.sendText(
                chatId,
                "⚠️ Код должен состоять из 6 цифр.\n\nПопробуйте ещё раз:",
                markup = keyboard(
                    row(
                        btn("🔄 Выслать код повторно", "auth:resend_reset_code"),
                        btn("❌ Отмена", "auth:cancel"),
                    ),
                ),
            )
            return
        }

        log.info("[BOT][AUTH] Код сброса пароля получен: chatId=$chatId")
        // Сохраняем код в pendingAction — временно используем это поле для хранения кода сброса
        session.pendingAction = trimmed
        session.step          = BotStep.WAITING_RESET_NEW_PASSWORD

        sender.sendText(
            chatId,
            "🔑 Введите новый пароль:\n\n" +
                    "Требования:\n" +
                    "• минимум $MIN_PASSWORD_LENGTH символов\n" +
                    "• строчные и заглавные латинские буквы\n" +
                    "• хотя бы одна цифра",
        )
    }

    /**
     * Получили новый пароль — валидируем, просим подтверждение.
     */
    fun handleWaitingResetNewPassword(chatId: Long, text: String) {
        val session  = sessions[chatId] ?: return
        val password = text.trim()

        val error = validatePassword(password)
        if (error != null) {
            sender.sendText(chatId, "⚠️ $error\n\nВведите пароль ещё раз:")
            return
        }

        session.resetNewPassword = password
        session.step             = BotStep.WAITING_RESET_NEW_PASSWORD_CONFIRM
        sender.sendText(chatId, "🔑 Повторите новый пароль:")
    }

    /**
     * Подтверждение нового пароля — вызываем resetPassword.
     */
    fun handleWaitingResetNewPasswordConfirm(chatId: Long, text: String, from: User) {
        val session  = sessions[chatId] ?: return
        val confirm  = text.trim()
        val newPwd   = session.resetNewPassword ?: return
        val code     = session.pendingAction    ?: return  // код сброса хранится в pendingAction
        val tgUser   = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        if (newPwd != confirm) {
            session.step             = BotStep.WAITING_RESET_NEW_PASSWORD
            session.resetNewPassword = null
            sender.sendText(chatId, "❌ Пароли не совпадают.\n\nВведите новый пароль заново:")
            return
        }

        log.info("[BOT][AUTH] Попытка сброса пароля: $tgUser")

        runCatching {
            authService.resetPassword(
                io.getaimly.backend.auth.dto.ResetPasswordRequest(
                    code            = code,
                    newPassword     = newPwd,
                    confirmPassword = confirm,
                )
            )
        }.onSuccess { authResponse ->
            log.info("[BOT][AUTH] ✅ Пароль успешно сброшен: $tgUser email=${authResponse.email}")
            sessions.remove(chatId)

            // Привязываем Telegram если ещё не привязан
            if (!authResponse.telegramLinked) {
                authService.linkTelegramDirect(authResponse.userId, from.id, from.userName)
                log.info("[BOT][AUTH] Telegram привязан после сброса пароля: userId=${authResponse.userId} tgId=${from.id}")
            }

            val name = authResponse.firstName?.takeIf { it.isNotBlank() } ?: authResponse.email.split("@").first()
            sender.sendText(
                chatId,
                "✅ Пароль успешно изменён!\n\n" +
                        "Теперь вы можете войти на сайте getaimly.io с новым паролем.",
            )
            showMainMenu(chatId, name, from.id)
        }.onFailure { e ->
            val msg = when (e) {
                is BadRequestException -> e.message ?: "Неверный или истёкший код"
                else -> {
                    log.error("[BOT][AUTH] Ошибка сброса пароля: $tgUser", e)
                    "Произошла ошибка. Попробуйте позже."
                }
            }
            log.warn("[BOT][AUTH] Ошибка сброса пароля: $tgUser причина=${e.message}")
            sessions.remove(chatId)
            sender.sendText(
                chatId,
                "❌ $msg\n\nНачните процедуру сброса пароля заново:",
                markup = keyboard(row(btn("🔑 Сбросить пароль", "auth:forgot_password"))),
            )
        }
    }

    /**
     * Повторная отправка кода сброса пароля.
     */
    fun resendResetCode(chatId: Long, msgId: Int) {
        val session = sessions[chatId]
        if (session == null || session.step != BotStep.WAITING_RESET_CODE) {
            log.warn("[BOT][AUTH] Попытка resend reset code без активной сессии: chatId=$chatId")
            sender.editText(
                chatId, msgId,
                "Сессия устарела. Начните сброс пароля заново:",
                markup = keyboard(row(btn("🔑 Сбросить пароль", "auth:forgot_password"))),
            )
            return
        }

        val email = session.resetEmail
        if (email == null) {
            sessions.remove(chatId)
            sender.editText(
                chatId, msgId,
                "Сессия устарела. Начните сброс пароля заново:",
                markup = keyboard(row(btn("🔑 Сбросить пароль", "auth:forgot_password"))),
            )
            return
        }

        log.info("[BOT][AUTH] Повторная отправка кода сброса пароля: chatId=$chatId email=$email")

        runCatching {
            authService.requestPasswordReset(email)
        }.onSuccess {
            sender.editText(
                chatId, msgId,
                "📧 Новый код отправлен на $email\n\nВведите 6-значный код из письма:",
                markup = keyboard(
                    row(
                        btn("🔄 Выслать код повторно", "auth:resend_reset_code"),
                        btn("❌ Отмена", "auth:cancel"),
                    ),
                ),
            )
        }.onFailure { e ->
            val msg = when (e) {
                is TooManyRequestsException -> e.message ?: "Слишком много запросов"
                else -> "Не удалось отправить код. Попробуйте позже."
            }
            log.warn("[BOT][AUTH] Ошибка повторной отправки кода сброса: chatId=$chatId причина=${e.message}")
            sender.editText(
                chatId, msgId,
                "⚠️ $msg",
                markup = keyboard(
                    row(
                        btn("🔄 Выслать код повторно", "auth:resend_reset_code"),
                        btn("❌ Отмена", "auth:cancel"),
                    ),
                ),
            )
        }
    }


    // ─── Вспомогательные ──────────────────────────────────────────────────────────

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

    fun clearPendingReferral(chatId: Long) {
        pendingReferralCodes.remove(chatId)
    }
}