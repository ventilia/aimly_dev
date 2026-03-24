package io.getaimly.backend.bot

import io.getaimly.backend.ai.AiService
import io.getaimly.backend.auth.AuthService
import io.getaimly.backend.lead.ChatSearchService
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.lead.LeadService
import io.getaimly.backend.lead.LeadStatus
import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.UserRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
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
    private val aiService: AiService,
    private val chatSearchService: ChatSearchService,
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log            = LoggerFactory.getLogger(AimlyBot::class.java)
    private val telegramClient = OkHttpTelegramClient(token)
    private val sender         = BotSender(telegramClient)

    private val sessions = ConcurrentHashMap<Long, UserSession>()


    private val paymentHandler = BotPaymentHandler(
        sender           = sender,
        userRepository   = userRepository,
        expiryRepository = expiryRepository,
    )

    private val authHandler = BotAuthHandler(
        sender         = sender,
        sessions       = sessions,
        userRepository = userRepository,
        leadRepository = leadRepository,
        authService    = authService,
        paymentHandler = paymentHandler,
    )

    private val leadsHandler = BotLeadsHandler(
        sender                 = sender,
        userRepository         = userRepository,
        leadRepository         = leadRepository,
        subscriptionRepository = subscriptionRepository,
        leadService            = leadService,
    )

    private val chatsHandler = BotChatsHandler(
        sender                 = sender,
        sessions               = sessions,
        userRepository         = userRepository,
        subscriptionRepository = subscriptionRepository,
        leadService            = leadService,
    )

    private val keywordsHandler = BotKeywordsHandler(
        sender            = sender,
        sessions          = sessions,
        userRepository    = userRepository,
        keywordRepository = keywordRepository,
        leadService       = leadService,
        aiService         = aiService,
    )

    private val profileHandler = BotProfileHandler(
        sender                 = sender,
        sessions               = sessions,
        userRepository         = userRepository,
        leadRepository         = leadRepository,
        subscriptionRepository = subscriptionRepository,
        keywordRepository      = keywordRepository,
        expiryRepository       = expiryRepository,
        authService            = authService,
        leadService            = leadService,
    )

    private val chatSearchHandler = BotChatSearchHandler(
        sender                 = sender,
        sessions               = sessions,
        userRepository         = userRepository,
        subscriptionRepository = subscriptionRepository,
        keywordRepository      = keywordRepository,
        leadService            = leadService,
        chatSearchService      = chatSearchService,
    )


    @PostConstruct
    fun init() = log.info("AimlyBot запущен: @$botUsername")

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    fun cleanupStaleSessions() {
        val threshold = LocalDateTime.now().minusMinutes(10)
        val before    = sessions.size
        val removed   = sessions.entries.removeIf { it.value.createdAt.isBefore(threshold) }
        if (removed) {
            log.info("[BOT] Очистка устаревших сессий: было=$before осталось=${sessions.size}")
        }
    }

    override fun getBotToken(): String = token
    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = this

    fun sendText(chatId: Long, text: String) {
        sender.sendText(chatId, text)
    }

    // перегрузка
    fun sendText(chatId: Long, text: String, markup: InlineKeyboardMarkup?) {
        sender.sendText(chatId, text, markup)
    }


    override fun consume(update: Update) {
        try {
            when {
                update.hasMessage() && update.message.hasText() -> handleMessage(update)
                update.hasCallbackQuery()                       -> handleCallback(update)
            }
        } catch (e: Exception) {
            val ctx = when {
                update.hasMessage()       -> "сообщение от tgId=${update.message.from.id} (@${update.message.from.userName ?: "—"})"
                update.hasCallbackQuery() -> "callback \"${update.callbackQuery.data}\" от tgId=${update.callbackQuery.from.id} (@${update.callbackQuery.from.userName ?: "—"})"
                else                      -> "unknown update"
            }
            log.error("[BOT] ❌ Ошибка обработки update #${update.updateId} ($ctx): ${e.message}", e)
        }
    }

    private fun handleMessage(update: Update) {
        val msg        = update.message
        val chatId     = msg.chatId
        val text       = msg.text.trim()
        val from       = msg.from
        val tgUser     = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"
        val startToken = if (text.startsWith("/start ")) text.removePrefix("/start ").trim() else null

        // Не логируем пароль в открытом виде
        val session = sessions[chatId]
        if (session?.step == BotStep.WAITING_PASSWORD) {
            log.info("[BOT][MSG] $tgUser → [ПАРОЛЬ СКРЫТ]")
        } else {
            log.info("[BOT][MSG] $tgUser → «${text.take(100)}»")
        }

        when {
            text == "/start"    -> authHandler.handleStart(chatId, from, null)
            startToken != null  -> { sessions.remove(chatId); authHandler.handleStart(chatId, from, startToken) }
            text == "/cancel"   -> handleCancel(chatId, tgUser)
            text == "/help"     -> sendHelp(chatId)
            text == "/status"   -> sendStatus(chatId, from.id)
            text == "/leads"    -> {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) leadsHandler.showLeadsMenu(chatId, from.id)
                else authHandler.showWelcome(chatId, from.firstName)
            }
            text == "/pay"      -> {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) paymentHandler.sendPaymentMessage(chatId, from.id)
                else authHandler.handleStart(chatId, from, "pay")
            }
            text == "/chats"    -> {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) sender.sendText(chatId, "Для управления чатами используйте /start → Чаты.")
                else authHandler.showWelcome(chatId, from.firstName)
            }
            text == "/profile"  -> {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) sender.sendText(chatId, "Для управления профилем используйте /start → Профиль.")
                else authHandler.showWelcome(chatId, from.firstName)
            }
            text == "/keywords" -> {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) sender.sendText(chatId, "Для управления ключевыми словами используйте /start → Ключевые слова.")
                else authHandler.showWelcome(chatId, from.firstName)
            }

            sessions.containsKey(chatId) -> handleSessionInput(chatId, text, from)

            else -> {
                val user = userRepository.findByTelegramId(from.id).orElse(null)
                if (user != null) authHandler.showMainMenu(chatId, user.firstName)
                else authHandler.showWelcome(chatId, from.firstName)
            }
        }
    }

    private fun handleSessionInput(
        chatId: Long,
        text: String,
        from: org.telegram.telegrambots.meta.api.objects.User,
    ) {
        val session = sessions[chatId] ?: return
        when (session.step) {
            BotStep.WAITING_EMAIL                -> authHandler.handleWaitingEmail(chatId, text)
            BotStep.WAITING_PASSWORD             -> authHandler.handleWaitingPassword(chatId, text, from)
            BotStep.WAITING_CHAT_LINK            -> chatsHandler.handleChatLinkInput(chatId, text, from)
            BotStep.WAITING_KEYWORD              -> keywordsHandler.handleKeywordInput(chatId, text, from)
            BotStep.WAITING_CONTEXT              -> profileHandler.handleContextInput(chatId, text)
            BotStep.WAITING_AI_KEYWORD_CONFIRM   -> {  }
            BotStep.WAITING_CHAT_SEARCH_QUERY    -> chatSearchHandler.handleSearchQueryInput(chatId, text, from)
        }
    }

    private fun handleCancel(chatId: Long, tgUser: String) {
        val session = sessions.remove(chatId)
        log.info("[BOT] /cancel: $tgUser был_шаг=${session?.step ?: "нет сессии"}")
        val msgId = session?.msgId ?: 0
        if (msgId != 0) {
            sender.editText(chatId, msgId, "Отменено. Нажмите /start чтобы начать.")
        } else {
            sender.sendText(chatId, "Отменено. Нажмите /start чтобы начать.")
        }
    }

    private fun handleCallback(update: Update) {
        val q      = update.callbackQuery
        val chatId = q.message.chatId
        val msgId  = q.message.messageId
        val from   = q.from
        val data   = q.data ?: return
        val tgUser = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        if (data == "noop") return

        log.info("[BOT][CB] $tgUser → \"$data\"")

        when {
            data == "auth:login"     -> authHandler.startLoginFlow(chatId, msgId)
            data == "auth:cancel"    -> {
                sessions.remove(chatId)
                log.info("[BOT][CB] Отмена авторизации: $tgUser")
                sender.editText(chatId, msgId, "Отменено. Нажмите /start чтобы начать.")
            }
            data == "auth:retry"     -> authHandler.startLoginFlow(chatId, msgId)
            data == "auth:retry_pay" -> {
                sessions[chatId] = UserSession(step = BotStep.WAITING_EMAIL, msgId = msgId, pendingAction = "pay")
                sender.editText(
                    chatId, msgId,
                    "🔐 *Вход в аккаунт*\n\nВведите email от вашего аккаунта getaimly.io:",
                    markup        = keyboard(row(btn("❌ Отмена", "auth:cancel"))),
                    parseMarkdown = true,
                )
            }

            data == "menu:back"     -> authHandler.showMainMenuEdit(chatId, msgId, from)
            data == "menu:leads"    -> leadsHandler.showLeadsMenu(chatId, from.id, msgId)
            data == "menu:chats"    -> chatsHandler.showChats(chatId, msgId, from.id)
            data == "menu:keywords" -> keywordsHandler.showKeywords(chatId, msgId, from.id)
            data == "menu:profile"  -> profileHandler.showProfile(chatId, msgId, from)
            data == "menu:help"     -> {
                sender.editText(chatId, msgId, buildHelpText(), parseMarkdown = true)
                sender.sendText(chatId, "Используйте /start для возврата в главное меню.")
            }

            data == "payment:plans" -> paymentHandler.showPlans(chatId, msgId, from.id)
            data == "leads:new"      -> leadsHandler.showLeadsList(chatId, msgId, from.id, 0, "NEW")
            data == "leads:all"      -> leadsHandler.showLeadsList(chatId, msgId, from.id, 0, null)
            data == "leads:readall"  -> leadsHandler.markAllRead(chatId, msgId, from.id)
            data.startsWith("leads:page:") -> {
                val parts = data.removePrefix("leads:page:").split(":")
                val pg    = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val flt   = parts.getOrNull(1)?.takeIf { it != "null" && it != "all" }
                leadsHandler.showLeadsList(chatId, msgId, from.id, pg, flt)
            }
            data.startsWith("lead:open:")    -> leadsHandler.showLeadDetail(chatId, msgId, from.id, data.removePrefix("lead:open:").toLongOrNull() ?: 0)
            data.startsWith("lead:viewed:")  -> { leadsHandler.changeLeadStatus(from.id, data.removePrefix("lead:viewed:").toLongOrNull() ?: 0, "VIEWED");  leadsHandler.showLeadsMenu(chatId, from.id, msgId) }
            data.startsWith("lead:replied:") -> { leadsHandler.changeLeadStatus(from.id, data.removePrefix("lead:replied:").toLongOrNull() ?: 0, "REPLIED"); leadsHandler.showLeadsMenu(chatId, from.id, msgId) }
            data.startsWith("lead:ignored:") -> { leadsHandler.changeLeadStatus(from.id, data.removePrefix("lead:ignored:").toLongOrNull() ?: 0, "IGNORED"); leadsHandler.showLeadsMenu(chatId, from.id, msgId) }
            data == "chat:add"           -> chatsHandler.startAddChat(chatId, msgId)
            data == "chat:del:cancel"    -> chatsHandler.showChats(chatId, msgId, from.id)
            data.startsWith("chat:page:")        -> chatsHandler.showChats(chatId, msgId, from.id, data.removePrefix("chat:page:").toIntOrNull() ?: 0)
            data.startsWith("chat:del:confirm:") -> { chatsHandler.deleteChat(from.id, data.removePrefix("chat:del:confirm:").toLongOrNull() ?: 0); chatsHandler.showChats(chatId, msgId, from.id) }
            data.startsWith("chat:del:")         -> chatsHandler.showDeleteChatConfirm(chatId, msgId, from.id, data.removePrefix("chat:del:").toLongOrNull() ?: 0)
            data == "csearch:start"           -> chatSearchHandler.showSearchScreen(chatId, msgId, from.id)
            data == "csearch:manual"          -> chatSearchHandler.startManualSearch(chatId, msgId, null)
            data == "csearch:manual:chat"     -> chatSearchHandler.startManualSearch(chatId, msgId, "chat")
            data == "csearch:manual:channel"  -> chatSearchHandler.startManualSearch(chatId, msgId, "channel")
            data == "csearch:by_keywords"     -> chatSearchHandler.searchByKeywords(chatId, msgId, from.id)
            data == "csearch:by_context"      -> chatSearchHandler.searchByContext(chatId, msgId, from.id)
            data == "csearch:add_all"         -> chatSearchHandler.addAll(chatId, msgId, from.id)

            data.startsWith("csearch:page:") -> {
                val pg = data.removePrefix("csearch:page:").toIntOrNull() ?: 0
                chatSearchHandler.showResults(chatId, msgId, from.id, pg)
            }
            data.startsWith("csearch:add:") -> {
                val idx = data.removePrefix("csearch:add:").toIntOrNull() ?: return
                chatSearchHandler.addFromSearch(chatId, msgId, from.id, idx)
            }
            data.startsWith("csearch:skip:") -> {
                val idx = data.removePrefix("csearch:skip:").toIntOrNull() ?: return
                chatSearchHandler.dismissResult(chatId, msgId, from.id, idx)
            }
            data.startsWith("csearch:add_page:") -> {
                val pg = data.removePrefix("csearch:add_page:").toIntOrNull() ?: 0
                chatSearchHandler.addPage(chatId, msgId, from.id, pg)
            }

            data == "kw:add"          -> keywordsHandler.startAddKeyword(chatId, msgId, from.id)
            data == "kw:del:cancel"   -> keywordsHandler.showKeywords(chatId, msgId, from.id)
            data.startsWith("kw:page:")          -> keywordsHandler.showKeywords(chatId, msgId, from.id, data.removePrefix("kw:page:").toIntOrNull() ?: 0)
            data.startsWith("kw:del:confirm:")   -> { keywordsHandler.deleteKeyword(from.id, data.removePrefix("kw:del:confirm:").toLongOrNull() ?: 0); keywordsHandler.showKeywords(chatId, msgId, from.id) }
            data.startsWith("kw:del:")           -> keywordsHandler.showDeleteKeywordConfirm(chatId, msgId, from.id, data.removePrefix("kw:del:").toLongOrNull() ?: 0)
            data == "kw:ai:generate"             -> keywordsHandler.startAiGeneration(chatId, msgId, from.id)
            data == "kw:ai:accept_all"           -> keywordsHandler.acceptAllAiSuggestions(chatId, msgId, from.id)
            data == "kw:ai:reject_all"           -> keywordsHandler.rejectAllAiSuggestions(chatId, msgId)
            data.startsWith("kw:ai:accept_page:") -> keywordsHandler.acceptAiPage(chatId, msgId, from.id, data.removePrefix("kw:ai:accept_page:").toIntOrNull() ?: 0)
            data.startsWith("kw:ai:accept:")      -> keywordsHandler.acceptAiSuggestion(chatId, msgId, from.id, data.removePrefix("kw:ai:accept:").toIntOrNull() ?: 0)
            data.startsWith("kw:ai:reject:")      -> keywordsHandler.rejectAiSuggestion(chatId, msgId, from.id, data.removePrefix("kw:ai:reject:").toIntOrNull() ?: 0)
            data.startsWith("kw:ai:page:")        -> keywordsHandler.setAiPage(chatId, msgId, from.id, data.removePrefix("kw:ai:page:").toIntOrNull() ?: 0)
            data == "kw:toggle_service_offers"   -> keywordsHandler.toggleServiceOffers(chatId, msgId, from.id)
            data == "profile:edit_context"      -> profileHandler.startEditContext(chatId, msgId, from.id)
            data == "profile:clear_context"     -> profileHandler.clearContext(chatId, msgId, from.id)
            data == "profile:unlink_tg"         -> profileHandler.showUnlinkConfirm(chatId, msgId)
            data == "profile:unlink_tg:confirm" -> profileHandler.confirmUnlink(chatId, msgId, from.id)

            else -> log.warn("[BOT][CB] Неизвестный callback: \"$data\" от $tgUser")
        }
    }


    fun notifyNewLead(
        telegramChatId: Long,
        leadId: Long,
        chatTitle: String,
        text: String,
        link: String,
        keyword: String,
        authorUsername: String = "",
        authorName: String = "",
        isHistorical: Boolean = false,
    ) {
        val preview    = text.take(300).let { if (text.length > 300) "$it…" else it }
        val authorLine = when {
            authorUsername.isNotBlank() -> "\n👤 Автор: @$authorUsername"
            authorName.isNotBlank()     -> "\n👤 Автор: $authorName"
            else                        -> ""
        }
        val headline = if (isHistorical) "🕐 Старый лид (за последние 24 ч)" else "🎯 Новый лид!"

        val rows = mutableListOf<InlineKeyboardRow>()
        if (link.isNotBlank()) {
            rows.add(row(InlineKeyboardButton.builder()
                .text("🔗 Открыть в Telegram")
                .url(buildTgDeepLink(link))
                .build()))
        }
        rows.add(row(
            btn("📄 Подробнее", "lead:open:$leadId"),
            btn("📋 Все лиды", "menu:leads"),
        ))

        runCatching {
            telegramClient.execute(
                SendMessage.builder()
                    .chatId(telegramChatId.toString())
                    .text(
                        "$headline\n\n" +
                                "💬 Чат: $chatTitle\n" +
                                "🔑 Ключевое слово: «$keyword»" +
                                authorLine + "\n\n" +
                                preview
                    )
                    .replyMarkup(InlineKeyboardMarkup(rows))
                    .build()
            )
            log.info("[BOT][NOTIFY] ✅ Уведомление отправлено: tgChatId=$telegramChatId leadId=$leadId keyword=\"$keyword\" исторический=$isHistorical")
        }.onFailure {
            log.warn("[BOT][NOTIFY] ❌ Ошибка отправки уведомления: tgChatId=$telegramChatId leadId=$leadId причина=${it.message}")
        }
    }


    fun sendStatus(chatId: Long, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            sender.sendText(chatId, "Аккаунт не привязан.\n\nИспользуйте /start чтобы войти.")
            return
        }
        val chats    = subscriptionRepository.countByUserIdAndIsActiveTrue(user.id)
        val keywords = keywordRepository.findByUserIdAndIsActiveTrue(user.id).size
        val newLeads = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        val subLine  = when (user.subscriptionStatus) {
            "ACTIVE" -> "✅ Активна (${user.subscriptionPlan ?: "—"})"
            "TRIAL"  -> "🔵 Пробный период"
            else     -> "❌ Нет подписки"
        }
        log.info("[BOT] /status: userId=${user.id} email=${user.email} подписка=${user.subscriptionStatus} чатов=$chats ключевых_слов=$keywords новых_лидов=$newLeads")
        sender.sendText(
            chatId,
            "📊 Статус аккаунта\n\n" +
                    "📧 ${user.email}\n" +
                    "📋 Подписка: $subLine\n\n" +
                    "💬 Чатов: $chats\n" +
                    "🔍 Ключевых слов: $keywords\n" +
                    "📬 Новых лидов: $newLeads",
        )
    }

    private fun sendHelp(chatId: Long) =
        sender.sendText(chatId, buildHelpText())

    private fun buildHelpText() =
        "📖 Помощь AIMLY\n\n" +
                "🤖 Бот повторяет весь функционал сайта \n" +
                "Для максимального удобства рекомендуем использовать веб-версию:\n" +
                "🌐 ${BotAuthHandler.SITE_URL}\n\n" +
                "Команды:\n" +
                "/start — главное меню\n" +
                "/leads — список лидов\n" +
                "/chats — управление чатами\n" +
                "/keywords — ключевые слова\n" +
                "/profile — профиль\n" +
                "/pay — оплатить подписку\n" +
                "/status — статус аккаунта\n" +
                "/cancel — отменить действие\n" +
                "/help — эта справка\n\n" +
                "Как работает:\n" +
                "1. Добавьте Telegram-чаты (вручную или через AI-поиск)\n" +
                "2. Укажите ключевые слова («ищу дизайнера» и т.д.)\n" +
                "3. При совпадении вы получите уведомление с лидом\n\n" +
                "💬 Поддержка: @aimly_support"

    private fun buildTgDeepLink(link: String): String {
        val clean = link
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("t.me/")

        if (clean.startsWith("c/")) {
            val parts  = clean.removePrefix("c/").split("/")
            val cId    = parts.getOrNull(0) ?: return link
            val postId = parts.getOrNull(1) ?: return link
            if (cId.toLongOrNull() != null && postId.toLongOrNull() != null) {
                return "tg://privatepost?channel=$cId&post=$postId"
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
}