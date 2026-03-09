package io.getaimly.backend.bot

import io.getaimly.backend.auth.AuthService
import io.getaimly.backend.auth.BadRequestException
import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.auth.LoginResult
import io.getaimly.backend.auth.TooManyRequestsException
import io.getaimly.backend.auth.UnauthorizedException
import io.getaimly.backend.auth.dto.LoginRequest
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.lead.LeadDto
import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.lead.LeadService
import io.getaimly.backend.lead.LeadStatus
import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.UserRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Component
class AimlyBot(
    @Value("\${telegram.bot.token}")    private val token: String,
    @Value("\${telegram.bot.username}") private val botUsername: String,
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val leadRepository: LeadRepository,
    private val subscriptionRepository: ChatSubscriptionRepository,
    private val keywordRepository: KeywordRepository,
    private val leadService: LeadService,
    private val expiryRepository: SubscriptionExpiryRepository,
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(AimlyBot::class.java)
    private val telegramClient: TelegramClient = OkHttpTelegramClient(token)


    private val sessions = ConcurrentHashMap<Long, UserSession>()

    @PostConstruct
    fun init() = log.info("AimlyBot запущен: @$botUsername")

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    fun cleanupStaleSessions() {
        val threshold = LocalDateTime.now().minusMinutes(10)
        sessions.entries.removeIf { it.value.createdAt.isBefore(threshold) }
    }

    override fun getBotToken() = token
    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = this



    override fun consume(update: Update) {
        try {
            when {
                update.hasMessage() && update.message.hasText() -> handleMessage(update)
                update.hasCallbackQuery() -> handleCallback(update)
            }
        } catch (e: Exception) {
            log.error("Ошибка обработки update ${update.updateId}: ${e.message}", e)
        }
    }



    private fun handleMessage(update: Update) {
        val msg    = update.message
        val chatId = msg.chatId
        val text   = msg.text.trim()
        val from   = msg.from

        val startToken = if (text.startsWith("/start ")) text.removePrefix("/start ").trim() else null

        when {
            text == "/start"      -> handleStart(chatId, from, null)
            startToken != null    -> { sessions.remove(chatId); handleStart(chatId, from, startToken) }
            text == "/cancel"     -> handleCancel(chatId)
            text == "/help"       -> sendHelp(chatId)
            text == "/status"     -> sendStatus(chatId, from.id)
            text == "/leads"      -> {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) showLeadsMenu(chatId, from.id)
                else showLoginPrompt(chatId, from.firstName)
            }
            sessions.containsKey(chatId) -> handleSessionStep(chatId, text, from)
            else -> {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) showMainMenu(chatId, user.firstName)
                else showLoginPrompt(chatId, from.firstName)
            }
        }
    }



    private fun handleStart(
        chatId: Long,
        from: org.telegram.telegrambots.meta.api.objects.User,
        linkToken: String?
    ) {
        if (linkToken != null) {
            val ok = authService.linkTelegram(linkToken, from.id, from.userName)
            if (ok) {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                val name = user?.firstName ?: from.firstName ?: "там"
                sendText(
                    chatId,
                    "✅ *Telegram успешно привязан к аккаунту AIMLY!*\n\n" +
                            "Теперь новые лиды будут приходить сюда автоматически.\n\n" +
                            "📌 Чтобы начать мониторинг:\n" +
                            "  1. Добавьте чаты → кнопка «💬 Чаты»\n" +
                            "  2. Укажите ключевые слова → «🔍 Ключевые слова»",
                    parseMarkdown = true
                )
                showMainMenu(chatId, name)
            } else {
                sendText(
                    chatId,
                    "❌ Ссылка недействительна или истекла.\n\n" +
                            "Запросите новую ссылку в личном кабинете:\n" +
                            "🌐 getaimly.io/dashboard → Профиль → Привязать Telegram"
                )
            }
            return
        }
        val existing = userRepository.findByTelegramId(from.id).orElse(null)
        if (existing != null) showMainMenu(chatId, existing.firstName)
        else showLoginPrompt(chatId, from.firstName)
    }


    private fun showLoginPrompt(chatId: Long, name: String?) {
        send(
            chatId,
            "👋 Привет, ${name ?: "там"}!\n\n" +
                    "AIMLY — сервис поиска лидов в Telegram-чатах по ключевым словам.\n\n" +
                    "Войдите в аккаунт, чтобы начать:",
            keyboard(row(btn("🔐 Войти в аккаунт", "auth:login")))
        )
    }

    private fun showMainMenu(chatId: Long, name: String?) {
        val user = userRepository.findByTelegramId(chatId).orElse(null)
        val newCount = user?.let { leadRepository.countByUserIdAndStatus(it.id, LeadStatus.NEW) } ?: 0
        val leadsLabel = if (newCount > 0) "📬 Лиды  •  $newCount новых" else "📋 Лиды"

        send(
            chatId,
            "👋 Привет, ${name ?: "там"}!\n\nЧто хотите сделать?",
            keyboard(
                row(btn(leadsLabel, "menu:leads")),
                row(btn("💬 Чаты",              "menu:chats"),
                    btn("🔍 Ключевые слова",    "menu:keywords")),
                row(btn("👤 Профиль",           "menu:profile"),
                    btn("❓ Помощь",            "menu:help")),
            )
        )
    }

    private fun sendHelp(chatId: Long) = sendText(
        chatId,
        "📖 *Помощь AIMLY*\n\n" +
                "*Команды:*\n" +
                "/start — главное меню\n" +
                "/leads — список лидов\n" +
                "/status — статус аккаунта\n" +
                "/cancel — отменить действие\n" +
                "/help — эта справка\n\n" +
                "*Как работает:*\n" +
                "1. Вы добавляете Telegram-чаты для мониторинга\n" +
                "2. Указываете ключевые слова (например: «ищу дизайнера»)\n" +
                "3. При совпадении вы получаете уведомление с лидом\n\n" +
                "💬 Поддержка: @aimly\\_support",
        parseMarkdown = true
    )


    private fun sendStatus(chatId: Long, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            sendText(chatId, "Аккаунт не привязан.\n\nИспользуйте /start чтобы войти.")
            return
        }
        val chats    = subscriptionRepository.countByUserIdAndIsActiveTrue(user.id)
        val keywords = keywordRepository.findByUserIdAndIsActiveTrue(user.id).size
        val newLeads = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        val subLine = when (user.subscriptionStatus) {
            "ACTIVE" -> "✅ Активна (${user.subscriptionPlan ?: "—"})"
            "TRIAL"  -> "🔵 Пробный период"
            else     -> "❌ Нет подписки"
        }
        sendText(
            chatId,
            "📊 *Статус аккаунта*\n\n" +
                    "📧 ${user.email.md()}\n" +
                    "📋 Подписка: ${subLine.md()}\n\n" +
                    "💬 Чатов: $chats\n" +
                    "🔍 Ключевых слов: $keywords\n" +
                    "📬 Новых лидов: $newLeads",
            parseMarkdown = true
        )
    }


    private fun handleCallback(update: Update) {
        val q      = update.callbackQuery
        val chatId = q.message.chatId
        val msgId  = q.message.messageId
        val data   = q.data
        val from   = q.from

        when {
            // Авторизация
            data == "auth:login"              -> startLoginFlow(chatId, msgId)
            data == "auth:cancel"             -> { sessions.remove(chatId); edit(chatId, msgId, "Отменено. Нажмите /start чтобы начать.") }
            data == "auth:retry"              -> startLoginFlow(chatId, msgId)

            // Главное меню
            data == "menu:leads"    -> showLeadsMenu(chatId, from.id, msgId)
            data == "menu:chats"    -> showChats(chatId, msgId, from.id)
            data == "menu:keywords" -> showKeywords(chatId, msgId, from.id)
            data == "menu:profile"  -> showProfile(chatId, msgId, from)
            data == "menu:help"     -> { edit(chatId, msgId, buildHelpText()); sendMainMenuAfter(chatId, from) }
            data == "menu:back"     -> showMainMenuEdit(chatId, msgId, from)

            data == "leads:new"     -> showLeadsList(chatId, msgId, from.id, 0, "NEW")
            data == "leads:all"     -> showLeadsList(chatId, msgId, from.id, 0, null)
            data.startsWith("leads:page:")    -> {
                val parts = data.removePrefix("leads:page:").split(":")
                val pg    = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val flt   = parts.getOrNull(1)
                showLeadsList(chatId, msgId, from.id, pg, flt)
            }
            data.startsWith("lead:open:")     -> showLeadDetail(chatId, msgId, from.id, data.removePrefix("lead:open:").toLongOrNull() ?: 0)
            data.startsWith("lead:viewed:")   -> { changeLeadStatus(from.id, data.removePrefix("lead:viewed:").toLongOrNull() ?: 0, "VIEWED"); showLeadsMenu(chatId, from.id, msgId) }
            data.startsWith("lead:replied:")  -> { changeLeadStatus(from.id, data.removePrefix("lead:replied:").toLongOrNull() ?: 0, "REPLIED"); showLeadsMenu(chatId, from.id, msgId) }
            data.startsWith("lead:ignored:")  -> { changeLeadStatus(from.id, data.removePrefix("lead:ignored:").toLongOrNull() ?: 0, "IGNORED"); showLeadsMenu(chatId, from.id, msgId) }
            data.startsWith("lead:back_list:") -> {
                val parts = data.removePrefix("lead:back_list:").split(":")
                val pg    = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val flt   = parts.getOrNull(1)
                showLeadsList(chatId, msgId, from.id, pg, flt)
            }

            data == "chat:add"                -> startAddChat(chatId, msgId)
            data.startsWith("chat:del:confirm:") -> { deleteChat(from.id, data.removePrefix("chat:del:confirm:").toLongOrNull() ?: 0); showChats(chatId, msgId, from.id) }
            data == "chat:del:cancel"                -> showChats(chatId, msgId, from.id)
            data.startsWith("chat:del:")             -> showDeleteChatConfirm(chatId, msgId, from.id, data.removePrefix("chat:del:").toLongOrNull() ?: 0)

            data == "kw:add"                  -> startAddKeyword(chatId, msgId)
            data.startsWith("kw:del:confirm:")  -> { deleteKeyword(from.id, data.removePrefix("kw:del:confirm:").toLongOrNull() ?: 0); showKeywords(chatId, msgId, from.id) }
            data == "kw:del:cancel"                 -> showKeywords(chatId, msgId, from.id)
            data.startsWith("kw:del:")              -> showDeleteKeywordConfirm(chatId, msgId, from.id, data.removePrefix("kw:del:").toLongOrNull() ?: 0)

            data == "profile:unlink_tg"         -> unlinkTelegram(chatId, msgId, from.id)
            data == "profile:unlink_tg:confirm" -> unlinkTelegramConfirm(chatId, msgId, from.id)

            // ─── AI-персонализация ───────────────────────────────────────────
            data == "profile:edit_context"      -> startEditContext(chatId, msgId, from.id)
            data == "profile:clear_context"     -> clearContext(chatId, msgId, from.id)

            else -> log.warn("Неизвестный callback: $data")
        }
    }


    private fun startLoginFlow(chatId: Long, msgId: Int) {
        sessions[chatId] = UserSession(step = BotStep.WAITING_EMAIL, msgId = msgId)
        edit(
            chatId, msgId,
            "🔐 *Вход в аккаунт*\n\nВведите email от вашего аккаунта getaimly.io:",
            keyboard(row(btn("❌ Отмена", "auth:cancel"))),
            parseMarkdown = true
        )
    }

    private fun handleSessionStep(
        chatId: Long,
        text: String,
        from: org.telegram.telegrambots.meta.api.objects.User
    ) {
        val session = sessions[chatId] ?: return
        when (session.step) {

            BotStep.WAITING_EMAIL -> {
                if (!text.contains('@') || !text.contains('.')) {
                    sendText(chatId, "⚠️ Некорректный формат email.\n\nПопробуйте ещё раз:")
                    return
                }
                session.email = text.lowercase().trim()
                session.step  = BotStep.WAITING_PASSWORD
                sendText(chatId, "🔑 Введите пароль:")
            }

            BotStep.WAITING_PASSWORD -> {
                val email = session.email ?: return
                sessions.remove(chatId)
                runCatching {
                    authService.login(LoginRequest(email = email, password = text), ipAddress = "telegram:$chatId")
                }.onSuccess { result ->
                    when (result) {
                        is LoginResult.Success -> {
                            if (!result.auth.telegramLinked)
                                authService.linkTelegramDirect(result.auth.userId, from.id, from.userName)
                            val name = result.auth.firstName ?: email.split("@").first()
                            sendText(chatId, "✅ Добро пожаловать, $name!\n\nТеперь лиды будут приходить сюда.")
                            showMainMenu(chatId, name)
                        }
                        is LoginResult.PendingVerification -> {
                            send(
                                chatId,
                                "📧 Email не подтверждён.\n\nПроверьте почту $email и перейдите по ссылке.",
                                keyboard(row(btn("🔄 Войти снова", "auth:retry"), btn("❌ Отмена", "auth:cancel")))
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
                    send(chatId, msg, keyboard(row(btn("🔄 Повторить", "auth:retry"), btn("❌ Отмена", "auth:cancel"))))
                }
            }

            BotStep.WAITING_CHAT_LINK -> {
                val savedMsgId = session.msgId
                sessions.remove(chatId)
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                    ?: run { sendText(chatId, "Нужно войти. /start"); return }

                runCatching { leadService.addSubscription(user, text) }
                    .onSuccess { sub ->
                        val title = sub.chatTitle.ifBlank { sub.chatLink }.md()
                        if (savedMsgId != 0) {
                            edit(
                                chatId, savedMsgId,
                                "✅ *Чат добавлен!*\n\n💬 $title\n\n" +
                                        "Userbot вступит в чат и начнёт мониторинг.\n" +
                                        "История за последние 24 часа будет проверена в фоне.",
                                keyboard(
                                    row(btn("➕ Добавить ещё", "chat:add")),
                                    row(btn("◀️ Назад", "menu:chats"))
                                ),
                                parseMarkdown = true
                            )
                        } else {
                            sendText(chatId, "✅ Чат добавлен: ${sub.chatTitle.ifBlank { sub.chatLink }}")
                            showMainMenu(chatId, user.firstName)
                        }
                    }
                    .onFailure { e ->
                        if (savedMsgId != 0) {
                            edit(
                                chatId, savedMsgId,
                                "❌ Не удалось добавить чат:\n${e.message}\n\nПроверьте ссылку и попробуйте снова.",
                                keyboard(
                                    row(btn("🔄 Попробовать снова", "chat:add")),
                                    row(btn("◀️ Назад", "menu:back"))
                                )
                            )
                        } else {
                            sendText(chatId, "❌ Ошибка: ${e.message}")
                            showMainMenu(chatId, user.firstName)
                        }
                    }
            }

            BotStep.WAITING_KEYWORD -> {
                val savedMsgId = session.msgId
                sessions.remove(chatId)
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                    ?: run { sendText(chatId, "Нужно войти. /start"); return }

                runCatching { leadService.addKeyword(user, text) }
                    .onSuccess { kw ->
                        val variants = kw.variants.take(3).joinToString(", ") { it.md() }
                        val variantsLine = if (kw.variants.isNotEmpty()) "\n\n🤖 AI добавит варианты поиска:\n_${variants}..._" else ""
                        if (savedMsgId != 0) {
                            edit(
                                chatId, savedMsgId,
                                "✅ *Ключевое слово добавлено!*\n\n🔍 «${kw.keyword.md()}»$variantsLine",
                                keyboard(
                                    row(btn("➕ Добавить ещё", "kw:add")),
                                    row(btn("◀️ К ключевым словам", "menu:keywords"))
                                ),
                                parseMarkdown = true
                            )
                        } else {
                            sendText(chatId, "✅ Добавлено: «${kw.keyword}»")
                            showMainMenu(chatId, user.firstName)
                        }
                    }
                    .onFailure { e ->
                        if (savedMsgId != 0) {
                            edit(
                                chatId, savedMsgId,
                                "❌ Ошибка: ${e.message}",
                                keyboard(
                                    row(btn("🔄 Попробовать снова", "kw:add")),
                                    row(btn("◀️ Назад", "menu:back"))
                                )
                            )
                        } else {
                            sendText(chatId, "❌ Ошибка: ${e.message}")
                            showMainMenu(chatId, user.firstName)
                        }
                    }
            }

            BotStep.WAITING_CONTEXT -> {
                val savedMsgId = session.msgId
                sessions.remove(chatId)

                val user = userRepository.findByTelegramId(from.id).orElse(null)
                    ?: run { sendText(chatId, "Нужно войти. /start"); return }

                val trimmed = text.trim()
                if (trimmed.length > 2000) {
                    sendText(
                        chatId,
                        "⚠️ Слишком длинный текст: ${trimmed.length} символов (макс. 2000).\n\nПожалуйста, сократите описание:"
                    )
                    sessions[chatId] = UserSession(step = BotStep.WAITING_CONTEXT, msgId = savedMsgId)
                    return
                }

                val u = userRepository.findById(user.id).orElse(null)
                    ?: run {
                        if (savedMsgId != 0) edit(chatId, savedMsgId, "❌ Ошибка. Попробуйте позже.")
                        else sendText(chatId, "❌ Ошибка.")
                        return
                    }

                u.businessContext = trimmed.ifBlank { null }
                userRepository.save(u)

                val confirmText = if (u.businessContext != null)
                    "✅ *Бизнес-контекст сохранён!*\n\n" +
                            "🤖 AI теперь учитывает ваш профиль при фильтрации лидов.\n\n" +
                            "📌 _Описание:_\n${trimmed.take(300).md()}${if (trimmed.length > 300) "…" else ""}"
                else
                    "✅ *Бизнес-контекст очищен.*\n\nAI будет работать без персонализации."

                if (savedMsgId != 0) {
                    edit(
                        chatId, savedMsgId,
                        confirmText,
                        keyboard(row(btn("◀️ К профилю", "menu:profile"))),
                        parseMarkdown = true
                    )
                } else {
                    sendText(chatId, if (u.businessContext != null) "✅ Бизнес-контекст сохранён!" else "✅ Бизнес-контекст очищен.")
                    showMainMenu(chatId, u.firstName)
                }
            }
        }
    }

    private fun handleCancel(chatId: Long) {
        sessions.remove(chatId)
        sendText(chatId, "✅ Действие отменено.\n\nИспользуйте /start чтобы вернуться в главное меню.")
    }


    private fun showLeadsMenu(chatId: Long, tgUserId: Long, msgId: Int? = null) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            val text = "Нужно войти. /start"
            if (msgId != null) edit(chatId, msgId, text) else sendText(chatId, text)
            return
        }

        val newCount   = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        val totalCount = leadRepository.countByUserId(user.id)

        val newLabel = if (newCount > 0) "📬 Новые лиды  •  $newCount" else "📬 Новых лидов нет"

        val text = "📋 *Лиды*\n\n" +
                "Всего лидов: $totalCount\n" +
                (if (newCount > 0) "🔴 Непросмотренных: $newCount\n" else "") +
                "\nВыберите раздел:"

        val kb = keyboard(
            row(btn(newLabel, "leads:new")),
            row(btn("📄 Все лиды", "leads:all")),
            row(btn("◀️ Главное меню", "menu:back")),
        )

        if (msgId != null) edit(chatId, msgId, text, kb, parseMarkdown = true)
        else send(chatId, text, kb)
    }

    private fun showLeadsList(chatId: Long, msgId: Int, tgUserId: Long, page: Int, statusFilter: String?) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            edit(chatId, msgId, "Нужно войти. /start")
            return
        }

        val pageDto = leadService.getLeads(user, statusFilter, page, 5)

        if (pageDto.content.isEmpty()) {
            edit(
                chatId, msgId,
                "📭 *Лидов нет*\n\n" +
                        (if (statusFilter == "NEW") "Новых лидов пока нет.\nДобавьте чаты и ключевые слова."
                        else "Лидов нет.\nДобавьте чаты и ключевые слова для мониторинга."),
                keyboard(row(btn("◀️ Назад", "menu:leads"))),
                parseMarkdown = true
            )
            return
        }

        val filterTag = statusFilter ?: "all"
        val title = when (statusFilter) {
            "NEW"     -> "📬 Новые лиды"
            "VIEWED"  -> "👁 Просмотренные"
            "REPLIED" -> "✅ Отвечено"
            else      -> "📋 Все лиды"
        }

        val sb = StringBuilder("$title  •  стр. ${page + 1}/${pageDto.totalPages}\n\n")
        val rows = mutableListOf<InlineKeyboardRow>()

        pageDto.content.forEachIndexed { idx, lead ->
            val num = idx + 1 + page * 5
            val statusIcon = when (lead.status) {
                "NEW"     -> "🔴"
                "VIEWED"  -> "🟡"
                "REPLIED" -> "🟢"
                "IGNORED" -> "⚫"
                else      -> "❓"
            }
            val aiIcon = when (lead.aiValid) {
                true  -> " ✨"
                false -> " 🚫"
                null  -> ""
            }
            val author = if (lead.authorUsername.isNotBlank()) "@${lead.authorUsername.md()}" else lead.authorName.md()
            val preview = lead.messageText.take(80).let { if (lead.messageText.length > 80) "$it…" else it }.md()
            val chatLabel = lead.chatTitle.ifBlank { lead.chatLink }.take(30).md()

            sb.append("$statusIcon$aiIcon *${num}. $author*\n")
            sb.append("💬 $chatLabel\n")
            sb.append("$preview\n")
            sb.append("🔑 «${lead.matchedKeyword.md()}»\n\n")

            rows.add(row(btn("$statusIcon №$num — Подробнее", "lead:open:${lead.id}")))
        }

        val pageRow = mutableListOf<InlineKeyboardButton>()
        if (page > 0)
            pageRow.add(btn("◀️", "leads:page:${page - 1}:$filterTag"))
        pageRow.add(btn("${page + 1}/${pageDto.totalPages}", "leads:page:$page:$filterTag"))
        if (page < pageDto.totalPages - 1)
            pageRow.add(btn("▶️", "leads:page:${page + 1}:$filterTag"))
        if (pageRow.size > 1) rows.add(InlineKeyboardRow(pageRow))

        rows.add(row(btn("◀️ Назад", "menu:leads")))

        edit(chatId, msgId, sb.toString().trimEnd(), InlineKeyboardMarkup(rows), parseMarkdown = true)
    }

    private fun showLeadDetail(chatId: Long, msgId: Int, tgUserId: Long, leadId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        val lead = leadRepository.findById(leadId).orElse(null)
        if (lead == null || lead.user.id != user.id) {
            edit(chatId, msgId, "❌ Лид не найден.")
            return
        }

        val sub = lead.subscriptionId?.let { subscriptionRepository.findById(it).orElse(null) }
        val chatLabel = (sub?.chatTitle?.ifBlank { sub.chatLink } ?: "").md()

        val statusIcon = when (lead.status) {
            LeadStatus.NEW     -> "🔴 Новый"
            LeadStatus.VIEWED  -> "🟡 Просмотрен"
            LeadStatus.REPLIED -> "🟢 Отвечено"
            LeadStatus.IGNORED -> "⚫ Архив"
        }
        val aiLine = when (lead.aiValid) {
            true  -> "\n🤖 AI: ✅ одобрил${lead.aiReason?.let { " — ${it.md()}" } ?: ""}"
            false -> "\n🤖 AI: ❌ отклонил${lead.aiReason?.let { " — ${it.md()}" } ?: ""}"
            null  -> ""
        }
        val author = buildString {
            append(lead.authorName.md())
            if (lead.authorUsername.isNotBlank()) append(" (@${lead.authorUsername.md()})")
        }
        val date = lead.foundAt.toLocalDate().toString()
        val time = "%02d:%02d".format(lead.foundAt.hour, lead.foundAt.minute)

        val text = buildString {
            append("📄 *Лид #${lead.id}*\n\n")
            append("$statusIcon$aiLine\n\n")
            append("👤 *Автор:* $author\n")
            if (chatLabel.isNotBlank()) append("💬 *Чат:* $chatLabel\n")
            append("🔑 *Ключевое слово:* «${lead.matchedKeyword.md()}»\n")
            append("📅 *Найден:* $date в $time\n\n")
            append("*Сообщение:*\n${lead.messageText.take(800).md()}")
            if (lead.messageText.length > 800) append("…")
        }

        val rows = mutableListOf<InlineKeyboardRow>()

        if (lead.messageLink.isNotBlank()) {
            val tgDeepLink = buildTgDeepLink(lead.messageLink)
            rows.add(row(InlineKeyboardButton.builder().text("🔗 Открыть в Telegram").url(tgDeepLink).build()))
        }

        when (lead.status) {
            LeadStatus.NEW -> {
                rows.add(row(
                    btn("👁 Просмотрен", "lead:viewed:${lead.id}"),
                    btn("✅ Отвечено",   "lead:replied:${lead.id}")
                ))
                rows.add(row(btn("🗃 В архив", "lead:ignored:${lead.id}")))
            }
            LeadStatus.VIEWED -> rows.add(row(
                btn("✅ Отвечено", "lead:replied:${lead.id}"),
                btn("🗃 В архив",  "lead:ignored:${lead.id}")
            ))
            LeadStatus.REPLIED -> rows.add(row(btn("🗃 В архив", "lead:ignored:${lead.id}")))
            else -> {}
        }
        rows.add(row(btn("◀️ К списку", "leads:all")))
        rows.add(row(btn("🏠 Главное меню", "menu:back")))

        edit(chatId, msgId, text, InlineKeyboardMarkup(rows), parseMarkdown = true)

        if (lead.status == LeadStatus.NEW) {
            runCatching { leadService.updateLeadStatus(user, leadId, "VIEWED") }
        }
    }

    private fun changeLeadStatus(tgUserId: Long, leadId: Long, status: String) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return
        runCatching { leadService.updateLeadStatus(user, leadId, status) }
    }


    private fun showChats(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        val chats = subscriptionRepository.findByUserIdAndIsActiveTrue(user.id)
        if (chats.isEmpty()) {
            edit(
                chatId, msgId,
                "💬 *Чаты для мониторинга*\n\nУ вас ещё нет добавленных чатов.\n\n" +
                        "Добавьте ссылку на Telegram-чат, и userbot начнёт его мониторить.",
                keyboard(
                    row(btn("➕ Добавить чат", "chat:add")),
                    row(btn("◀️ Главное меню", "menu:back"))
                ),
                parseMarkdown = true
            )
            return
        }

        val sb = StringBuilder("💬 *Мои чаты* (${chats.size})\n\n")
        chats.forEach { sub ->
            val status = if (sub.chatTgId != 0L) "🟢" else "🟡"
            sb.append("$status ${sub.chatTitle.ifBlank { sub.chatLink }.md()}\n")
        }
        sb.append("\n_Нажмите на чат чтобы удалить его_")

        val rows = mutableListOf<InlineKeyboardRow>()
        chats.forEach { sub ->
            val label = sub.chatTitle.ifBlank { sub.chatLink }.take(35)
            val status = if (sub.chatTgId != 0L) "🟢" else "🟡"
            rows.add(row(btn("$status 🗑 $label", "chat:del:${sub.id}")))
        }
        rows.add(row(btn("➕ Добавить чат", "chat:add"), btn("◀️ Главное меню", "menu:back")))

        edit(chatId, msgId, sb.toString(), InlineKeyboardMarkup(rows), parseMarkdown = true)
    }

    private fun startAddChat(chatId: Long, msgId: Int) {
        sessions[chatId] = UserSession(step = BotStep.WAITING_CHAT_LINK, msgId = msgId)
        edit(
            chatId, msgId,
            "💬 *Добавить чат*\n\n" +
                    "Отправьте ссылку на Telegram-чат или группу:\n\n" +
                    "📌 Примеры:\n" +
                    "• `t.me/smm_russia`\n" +
                    "• `@smm_russia`\n" +
                    "• `https://t.me/+abc123`",
            keyboard(row(btn("❌ Отмена", "menu:chats"))),
            parseMarkdown = true
        )
    }

    private fun showDeleteChatConfirm(chatId: Long, msgId: Int, tgUserId: Long, subId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        val sub = subscriptionRepository.findByUserIdAndIsActiveTrue(user.id).find { it.id == subId }
        val label = sub?.chatTitle?.ifBlank { sub.chatLink } ?: "этот чат"

        edit(
            chatId, msgId,
            "🗑 *Удалить чат?*\n\n" +
                    "💬 ${label.md()}\n\n" +
                    "Userbot покинет чат и мониторинг остановится.\n" +
                    "Лиды из этого чата сохранятся.",
            keyboard(
                row(
                    btn("✅ Да, удалить", "chat:del:confirm:$subId"),
                    btn("❌ Отмена",       "chat:del:cancel")
                )
            ),
            parseMarkdown = true
        )
    }

    private fun deleteChat(tgUserId: Long, subId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return
        runCatching { leadService.removeSubscription(user, subId) }
    }


    private fun showKeywords(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        val keywords = keywordRepository.findByUserIdAndIsActiveTrue(user.id)
        if (keywords.isEmpty()) {
            edit(
                chatId, msgId,
                "🔍 *Ключевые слова*\n\nУ вас ещё нет ключевых слов.\n\n" +
                        "Добавьте слово или фразу — при совпадении в чатах придёт уведомление.\n\n" +
                        "💡 Примеры: «ищу дизайнера», «нужен разработчик»",
                keyboard(
                    row(btn("➕ Добавить слово", "kw:add")),
                    row(btn("◀️ Главное меню", "menu:back"))
                ),
                parseMarkdown = true
            )
            return
        }

        val sb = StringBuilder("🔍 *Ключевые слова* (${keywords.size})\n\n")
        keywords.forEach { kw ->
            sb.append("• «${kw.keyword.md()}»")
            val variantsCount = kw.variants?.split(",")?.size ?: 0
            if (variantsCount > 0) sb.append(" +${variantsCount} вар.")
            sb.append("\n")
        }
        sb.append("\n_Нажмите на слово чтобы удалить_")

        val rows = mutableListOf<InlineKeyboardRow>()
        keywords.forEach { kw ->
            rows.add(row(btn("🗑 «${kw.keyword}»", "kw:del:${kw.id}")))
        }
        rows.add(row(btn("➕ Добавить", "kw:add"), btn("◀️ Главное меню", "menu:back")))

        edit(chatId, msgId, sb.toString(), InlineKeyboardMarkup(rows), parseMarkdown = true)
    }

    private fun startAddKeyword(chatId: Long, msgId: Int) {
        sessions[chatId] = UserSession(step = BotStep.WAITING_KEYWORD, msgId = msgId)
        edit(
            chatId, msgId,
            "🔍 *Добавить ключевое слово*\n\n" +
                    "Отправьте слово или фразу для поиска.\n\n" +
                    "💡 Примеры:\n" +
                    "• `ищу дизайнера`\n" +
                    "• `нужен разработчик`\n" +
                    "• `ищем копирайтера`\n\n" +
                    "🤖 AI автоматически создаст морфологические варианты.",
            keyboard(row(btn("❌ Отмена", "menu:keywords"))),
            parseMarkdown = true
        )
    }

    private fun showDeleteKeywordConfirm(chatId: Long, msgId: Int, tgUserId: Long, kwId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        val kw = keywordRepository.findByUserIdAndIsActiveTrue(user.id).find { it.id == kwId }
        val label = kw?.keyword ?: "это ключевое слово"

        edit(
            chatId, msgId,
            "🗑 *Удалить ключевое слово?*\n\n" +
                    "🔍 «${label.md()}»\n\n" +
                    "Лиды по этому слову больше приходить не будут.",
            keyboard(
                row(
                    btn("✅ Да, удалить", "kw:del:confirm:$kwId"),
                    btn("❌ Отмена",       "kw:del:cancel")
                )
            ),
            parseMarkdown = true
        )
    }

    private fun deleteKeyword(tgUserId: Long, kwId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return
        runCatching { leadService.removeKeyword(user, kwId) }
    }


    private fun showProfile(chatId: Long, msgId: Int, from: org.telegram.telegrambots.meta.api.objects.User) {
        val user = userRepository.findByTelegramId(from.id).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        val chatCount  = subscriptionRepository.countByUserIdAndIsActiveTrue(user.id)
        val kwCount    = keywordRepository.findByUserIdAndIsActiveTrue(user.id).size
        val totalLeads = leadRepository.countByUserId(user.id)
        val newLeads   = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        val expiry     = expiryRepository.findByUserId(user.id)
        val since      = user.createdAt?.toLocalDate()?.toString() ?: "—"

        val subLine = when {
            user.subscriptionStatus == "ACTIVE" -> {
                val till = expiry?.expiresAt?.toLocalDate()?.toString() ?: "—"
                "✅ ${user.subscriptionPlan ?: "ACTIVE"} — до $till"
            }
            user.subscriptionStatus == "TRIAL" -> "🔵 Пробный период"
            else -> "❌ Нет активной подписки"
        }

        val plan = user.subscriptionPlan
        val hasAiFeatures = plan == "START" || plan == "BUSINESS"

        val contextLine = when {
            !hasAiFeatures -> "\n🎯 *Бизнес-контекст AI:* 🔒 тариф START"
            !user.businessContext.isNullOrBlank() -> "\n🎯 *Бизнес-контекст AI:* ✅ задан (персонализирован)"
            else -> "\n🎯 *Бизнес-контекст AI:* не задан"
        }

        val text = "👤 *Профиль*\n\n" +
                "📧 ${user.email.md()}\n" +
                "👤 ${(user.firstName ?: "—").md()}\n" +
                "📱 Telegram: ✅ привязан\n" +
                "💳 Подписка: ${subLine.md()}" +
                contextLine + "\n\n" +
                "📊 *Статистика:*\n" +
                "💬 Чатов: $chatCount\n" +
                "🔍 Ключевых слов: $kwCount\n" +
                "📋 Всего лидов: $totalLeads  (🔴 новых: $newLeads)\n" +
                "📅 Аккаунт создан: $since"

        val rows = mutableListOf<InlineKeyboardRow>()

        if (user.subscriptionStatus != "ACTIVE") {
            rows.add(row(InlineKeyboardButton.builder()
                .text("💳 Выбрать тариф")
                .url("https://getaimly.io/checkout")
                .build()
            ))
        }


        rows.add(row(btn(
            if (hasAiFeatures && !user.businessContext.isNullOrBlank())
                "🎯 Изменить AI-персонализацию"
            else
                "🎯 Настроить AI-персонализацию",
            "profile:edit_context"
        )))

        rows.add(row(btn("🔓 Отвязать Telegram", "profile:unlink_tg")))
        rows.add(row(btn("◀️ Главное меню", "menu:back")))

        edit(chatId, msgId, text, InlineKeyboardMarkup(rows), parseMarkdown = true)
    }

    private fun unlinkTelegram(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        edit(
            chatId, msgId,
            "⚠️ *Отвязать Telegram?*\n\n" +
                    "После отвязки:\n" +
                    "• Мониторинг лидов остановится\n" +
                    "• Уведомления перестанут приходить\n\n" +
                    "Аккаунт getaimly.io сохранится. Вы сможете привязать снова.",
            keyboard(row(btn("✅ Да, отвязать", "profile:unlink_tg:confirm"), btn("❌ Отмена", "menu:profile"))),
            parseMarkdown = true
        )
    }

    private fun unlinkTelegramConfirm(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        runCatching { authService.unlinkTelegram(user.id) }
            .onSuccess {
                edit(
                    chatId, msgId,
                    "✅ Telegram отвязан.\n\nЧтобы снова привязать — зайдите в личный кабинет:\n" +
                            "🌐 getaimly.io/dashboard → Профиль"
                )
            }
            .onFailure { e ->
                edit(chatId, msgId, "❌ Ошибка: ${e.message}",
                    keyboard(row(btn("◀️ Назад", "menu:profile"))))
            }
    }


    private fun startEditContext(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        val plan = user.subscriptionPlan
        val hasAiFeatures = plan == "START" || plan == "BUSINESS"

        if (!hasAiFeatures) {
            edit(
                chatId, msgId,
                "🔒 *AI-персонализация — тариф START*\n\n" +
                        "На текущем тарифе AI фильтрует лиды по общим критериям.\n\n" +
                        "На тарифе *START* и выше вы можете задать описание своего бизнеса — " +
                        "AI будет отбирать только тех клиентов, которые ищут именно то, что вы предлагаете.\n\n" +
                        "💡 Пример: «Я frontend-разработчик на React, ищу клиентов с бюджетом от 50к»",
                keyboard(
                    row(InlineKeyboardButton.builder()
                        .text("💳 Перейти на START")
                        .url("https://getaimly.io/checkout")
                        .build()),
                    row(btn("◀️ Назад", "menu:profile"))
                ),
                parseMarkdown = true
            )
            return
        }

        val currentContext = user.businessContext
        val currentLine = when {
            currentContext.isNullOrBlank() -> "\n\n_Контекст пока не задан._"
            else -> "\n\n📌 *Текущий контекст:*\n_${currentContext.take(300).md()}${if (currentContext.length > 300) "…" else ""}_"
        }

        sessions[chatId] = UserSession(step = BotStep.WAITING_CONTEXT, msgId = msgId)
        edit(
            chatId, msgId,
            "🎯 *AI-персонализация*$currentLine\n\n" +
                    "Опишите ваш бизнес, услуги и целевую аудиторию.\n" +
                    "AI учитывает это при фильтрации — отбирает только подходящих клиентов.\n\n" +
                    "✏️ Отправьте новый текст (до 2000 символов):\n\n" +
                    "💡 _Пример: Я frontend-разработчик, специализируюсь на React и Next.js. " +
                    "Ищу клиентов, которым нужна разработка или доработка веб-приложений. " +
                    "Работаю с бюджетами от 50 000 ₽._",
            keyboard(
                row(btn("🗑 Очистить контекст", "profile:clear_context")),
                row(btn("❌ Отмена", "menu:profile"))
            ),
            parseMarkdown = true
        )
    }


    private fun clearContext(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { edit(chatId, msgId, "Нужно войти. /start"); return }

        sessions.remove(chatId) // на случай если была активна сессия WAITING_CONTEXT

        val u = userRepository.findById(user.id).orElse(null)
            ?: run { edit(chatId, msgId, "❌ Ошибка. Попробуйте позже."); return }

        u.businessContext = null
        userRepository.save(u)

        edit(
            chatId, msgId,
            "✅ *Бизнес-контекст очищен.*\n\nAI будет работать без персонализации.",
            keyboard(row(btn("◀️ К профилю", "menu:profile"))),
            parseMarkdown = true
        )
    }


    fun notifyNewLead(
        telegramChatId: Long,
        chatTitle: String,
        text: String,
        link: String,
        keyword: String,
        authorUsername: String = "",
        authorName: String = "",
    ) {
        val preview = text.take(300).let { if (text.length > 300) "$it…" else it }

        val authorLine = when {
            authorUsername.isNotBlank() -> "\n👤 *Автор:* @${authorUsername.md()}"
            authorName.isNotBlank()     -> "\n👤 *Автор:* ${authorName.md()}"
            else                        -> ""
        }

        val rows = mutableListOf<InlineKeyboardRow>()

        if (link.isNotBlank()) {
            val tgLink = buildTgDeepLink(link)
            rows.add(row(InlineKeyboardButton.builder().text("🔗 Открыть в Telegram").url(tgLink).build()))
        }
        rows.add(row(btn("📋 Все лиды", "menu:leads")))

        telegramClient.execute(
            SendMessage.builder()
                .chatId(telegramChatId.toString())
                .text(
                    "🎯 *Новый лид!*\n\n" +
                            "💬 *Чат:* ${chatTitle.md()}\n" +
                            "🔑 *Ключевое слово:* «${keyword.md()}»" +
                            authorLine + "\n\n" +
                            preview.md()
                )
                .parseMode("Markdown")
                .replyMarkup(InlineKeyboardMarkup(rows))
                .build()
        )
    }


    private fun buildTgDeepLink(link: String): String {
        val clean = link
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("t.me/")


        if (clean.startsWith("c/")) {
            val parts = clean.removePrefix("c/").split("/")
            val chatId = parts.getOrNull(0) ?: return link
            val postId = parts.getOrNull(1) ?: return link
            if (chatId.toLongOrNull() != null && postId.toLongOrNull() != null) {
                return "tg://privatepost?channel=$chatId&post=$postId"
            }
            return link
        }


        val parts = clean.split("/")
        if (parts.size >= 2) {
            val username = parts[0]
            val postId   = parts[1]

            if (postId.toLongOrNull() != null && !username.startsWith("+") && !username.startsWith("joinchat")) {
                return "tg://resolve?domain=$username&post=$postId"
            }
        }


        return link
    }


    private fun String.md(): String = this
        .replace("\\", "\\\\")
        .replace("_", "\\_")
        .replace("*", "\\*")
        .replace("`", "\\`")
        .replace("[", "\\[")

    fun sendText(chatId: Long, text: String, parseMarkdown: Boolean = false) {
        val b = SendMessage.builder().chatId(chatId.toString()).text(text)
        if (parseMarkdown) b.parseMode("Markdown")
        telegramClient.execute(b.build())
    }

    private fun send(chatId: Long, text: String, markup: InlineKeyboardMarkup, parseMarkdown: Boolean = false) {
        val b = SendMessage.builder().chatId(chatId.toString()).text(text).replyMarkup(markup)
        if (parseMarkdown) b.parseMode("Markdown")
        telegramClient.execute(b.build())
    }

    private fun edit(
        chatId: Long, msgId: Int, text: String,
        markup: InlineKeyboardMarkup? = null,
        parseMarkdown: Boolean = false
    ) {
        val b = EditMessageText.builder()
            .chatId(chatId.toString())
            .messageId(msgId)
            .text(text)
        markup?.let { b.replyMarkup(it) }
        if (parseMarkdown) b.parseMode("Markdown")
        runCatching { telegramClient.execute(b.build()) }
            .onFailure { log.debug("edit message failed: ${it.message}") }
    }



    private fun showMainMenuEdit(chatId: Long, msgId: Int, from: org.telegram.telegrambots.meta.api.objects.User) {
        val user = userRepository.findByTelegramId(from.id).orElse(null)
        val newCount = user?.let { leadRepository.countByUserIdAndStatus(it.id, LeadStatus.NEW) } ?: 0
        val leadsLabel = if (newCount > 0) "📬 Лиды  •  $newCount новых" else "📋 Лиды"
        edit(
            chatId, msgId,
            "👋 Привет, ${user?.firstName ?: "там"}!\n\nЧто хотите сделать?",
            keyboard(
                row(btn(leadsLabel, "menu:leads")),
                row(btn("💬 Чаты", "menu:chats"), btn("🔍 Ключевые слова", "menu:keywords")),
                row(btn("👤 Профиль", "menu:profile"), btn("❓ Помощь", "menu:help")),
            )
        )
    }

    private fun sendMainMenuAfter(chatId: Long, from: org.telegram.telegrambots.meta.api.objects.User) {
        val user = userRepository.findByTelegramId(from.id).orElse(null)
        showMainMenu(chatId, user?.firstName ?: from.firstName)
    }

    private fun buildHelpText() =
        "📖 *Помощь AIMLY*\n\n" +
                "/start — главное меню\n" +
                "/leads — список лидов\n" +
                "/status — статус аккаунта\n" +
                "/cancel — отменить действие\n" +
                "/help — справка\n\n" +
                "💬 Поддержка: @aimly_support"
}