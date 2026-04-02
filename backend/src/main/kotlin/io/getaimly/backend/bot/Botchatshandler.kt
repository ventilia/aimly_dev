package io.getaimly.backend.bot
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.LeadService
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import java.util.concurrent.ConcurrentHashMap

private const val CHATS_PAGE_SIZE = 5

class BotChatsHandler(
    private val sender: BotSender,
    private val sessions: ConcurrentHashMap<Long, UserSession>,
    private val userRepository: UserRepository,
    private val subscriptionRepository: ChatSubscriptionRepository,
    private val leadService: LeadService,
) {
    private val log = LoggerFactory.getLogger(BotChatsHandler::class.java)

    fun showChats(chatId: Long, msgId: Int, tgUserId: Long, page: Int = 0) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][CHATS] Не авторизован: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }
        val chats  = subscriptionRepository.findByUserIdAndIsActiveTrue(user.id)
        val plan   = user.subscriptionPlan
        val status = user.subscriptionStatus
        val hasSearch = plan in setOf("MINIMUM", "START") || status == "TRIAL"
        log.info("[BOT][CHATS] Список чатов: userId=${user.id} email=${user.email} активных=${chats.size} страница=$page")
        if (chats.isEmpty()) {
            val rows = mutableListOf<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow>()
            rows.add(row(btn("➕ Добавить чат вручную", "chat:add")))
            if (hasSearch) rows.add(row(btn("🔍 AI-поиск чатов", "csearch:start")))
            rows.add(row(btn("📥 Анализ экспорта", "chat:export")))
            rows.add(row(btn("◀️ Главное меню", "menu:back")))
            sender.editText(
                chatId, msgId,
                "💬 *Чаты для мониторинга*\nУ вас ещё нет добавленных чатов.\n" +
                "Добавьте ссылку на Telegram-чат, и userbot начнёт его мониторить.\n" +
                if (hasSearch) "💡 Используйте *AI-поиск*, чтобы найти подходящие чаты автоматически." else "",
                InlineKeyboardMarkup(rows),
                parseMarkdown = true,
            )
            return
        }
        val totalPages = (chats.size + CHATS_PAGE_SIZE - 1) / CHATS_PAGE_SIZE
        val safePage   = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val from       = safePage * CHATS_PAGE_SIZE
        val to         = (from + CHATS_PAGE_SIZE).coerceAtMost(chats.size)
        val pageChats  = chats.subList(from, to)
        val sb = StringBuilder("💬 *Мои чаты* (${chats.size})")
        if (totalPages > 1) sb.append("  •  стр. ${safePage + 1}/$totalPages")
        sb.append("\n")
        pageChats.forEach { sub ->
            val icon = if (sub.chatTgId != 0L) "🟢" else "🟡"
            sb.append("$icon ${sub.chatTitle.ifBlank { sub.chatLink }.md()}\n")
        }
        sb.append("\n_Нажмите на чат чтобы удалить его_")
        val rows = mutableListOf<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow>()
        pageChats.forEach { sub ->
            val icon  = if (sub.chatTgId != 0L) "🟢" else "🟡"
            val label = sub.chatTitle.ifBlank { sub.chatLink }.take(35)
            rows.add(row(btn("$icon 🗑 $label", "chat:del:${sub.id}")))
        }
        if (totalPages > 1) {
            val navBtns = mutableListOf<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>()
            if (safePage > 0)              navBtns.add(btn("◀️", "chat:page:${safePage - 1}"))
            navBtns.add(btn("${safePage + 1}/$totalPages", "noop"))
            if (safePage < totalPages - 1) navBtns.add(btn("▶️", "chat:page:${safePage + 1}"))
            rows.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(navBtns))
        }
        val actionBtns = mutableListOf<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>()
        actionBtns.add(btn("➕ Добавить", "chat:add"))
        if (hasSearch) actionBtns.add(btn("🔍 AI-поиск", "csearch:start"))
        actionBtns.add(btn("📥 Анализ экспорта", "chat:export"))
        rows.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(actionBtns))
        rows.add(row(btn("◀️ Главное меню", "menu:back")))
        sender.editText(chatId, msgId, sb.toString(), InlineKeyboardMarkup(rows), parseMarkdown = true)
    }

    fun startAddChat(chatId: Long, msgId: Int) {
        log.info("[BOT][CHATS] Начало добавления чата вручную: chatId=$chatId")
        sessions[chatId] = UserSession(step = BotStep.WAITING_CHAT_LINK, msgId = msgId)
        sender.editText(
            chatId, msgId,
            "💬 *Добавить чат*\n" +
            "Отправьте ссылку на Telegram-чат или группу:\n" +
            "📌 Примеры:\n" +
            "• `t.me/smm_russia`\n" +
            "• `@smm_russia`\n" +
            "• `https://t.me/+abc123`",
            keyboard(row(btn("❌ Отмена", "menu:chats"))),
            parseMarkdown = true,
        )
    }

    fun handleChatLinkInput(chatId: Long, text: String, from: org.telegram.telegrambots.meta.api.objects.User) {
        val session    = sessions.remove(chatId) ?: return
        val savedMsgId = session.msgId
        val tgUser     = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"
        val user       = userRepository.findByTelegramId(from.id).orElse(null)
            ?: run {
                log.warn("[BOT][CHATS] Не авторизован при вводе ссылки чата: chatId=$chatId tgId=${from.id}")
                sender.sendText(chatId, "Нужно войти. /start")
                return
            }
        val link = text.trim()
        log.info("[BOT][CHATS] Попытка добавить чат: userId=${user.id} email=${user.email} $tgUser link=\"$link\"")
        runCatching { leadService.addSubscription(user, link) }
            .onSuccess { sub ->
                val title = sub.chatTitle.ifBlank { sub.chatLink }
                log.info("[BOT][CHATS] ✅ Чат добавлен: userId=${user.id} email=${user.email} chatLink=\"${sub.chatLink}\" chatTitle=\"$title\"")
                if (savedMsgId != 0) {
                    sender.editText(
                        chatId, savedMsgId,
                        "✅ *Чат добавлен!*\n💬 ${title.md()}\n" +
                        "Userbot вступит в чат и начнёт мониторинг.\n" +
                        "История за последние 24 часа будет проверена в фоне.",
                        keyboard(
                            row(btn("➕ Добавить ещё", "chat:add")),
                            row(btn("◀️ К чатам",       "menu:chats")),
                        ),
                        parseMarkdown = true,
                    )
                } else {
                    sender.sendText(chatId, "✅ Чат добавлен: $title")
                }
            }
            .onFailure { e ->
                log.warn("[BOT][CHATS] ❌ Ошибка добавления чата: userId=${user.id} email=${user.email} link=\"$link\" причина=\"${e.message}\"")
                if (savedMsgId != 0) {
                    sender.editText(
                        chatId, savedMsgId,
                        "❌ Не удалось добавить чат:\n${e.message}\nПроверьте ссылку и попробуйте снова.",
                        keyboard(
                            row(btn("🔄 Попробовать снова", "chat:add")),
                            row(btn("◀️ Назад",              "menu:back")),
                        ),
                    )
                } else {
                    sender.sendText(chatId, "❌ Ошибка: ${e.message}")
                }
            }
    }

    fun showDeleteChatConfirm(chatId: Long, msgId: Int, tgUserId: Long, subId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][CHATS] Не авторизован при удалении чата: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }
        val sub   = subscriptionRepository.findByUserIdAndIsActiveTrue(user.id).find { it.id == subId }
        val label = sub?.chatTitle?.ifBlank { sub.chatLink } ?: "этот чат"
        log.info("[BOT][CHATS] Запрос удаления чата: userId=${user.id} email=${user.email} subId=$subId chatLink=\"${sub?.chatLink}\"")
        sender.editText(
            chatId, msgId,
            "🗑 *Удалить чат?*\n💬 ${label.md()}\n" +
            "Userbot покинет чат и мониторинг остановится.\n" +
            "Лиды из этого чата сохранятся.",
            keyboard(
                row(
                    btn("✅ Да, удалить", "chat:del:confirm:$subId"),
                    btn("❌ Отмена",       "chat:del:cancel"),
                ),
            ),
            parseMarkdown = true,
        )
    }

    fun deleteChat(tgUserId: Long, subId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][CHATS] Не авторизован при подтверждении удаления: tgId=$tgUserId subId=$subId")
            return
        }
        val sub = subscriptionRepository.findById(subId).orElse(null)
        log.info("[BOT][CHATS] Удаление чата: userId=${user.id} email=${user.email} subId=$subId chatLink=\"${sub?.chatLink}\"")
        runCatching { leadService.removeSubscription(user, subId) }
            .onSuccess { log.info("[BOT][CHATS] ✅ Чат удалён: userId=${user.id} email=${user.email} chatLink=\"${sub?.chatLink}\"") }
            .onFailure { log.warn("[BOT][CHATS] ❌ Ошибка удаления чата: userId=${user.id} email=${user.email} subId=$subId причина=${it.message}") }
    }
}