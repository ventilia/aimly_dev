package io.getaimly.backend.bot

import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.lead.LeadService
import io.getaimly.backend.lead.LeadStatus
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

private const val LEADS_PAGE_SIZE = 5


class BotLeadsHandler(
    private val sender: BotSender,
    private val userRepository: UserRepository,
    private val leadRepository: LeadRepository,
    private val subscriptionRepository: ChatSubscriptionRepository,
    private val leadService: LeadService,
) {

    private val log = LoggerFactory.getLogger(BotLeadsHandler::class.java)



    fun showLeadsMenu(chatId: Long, tgUserId: Long, msgId: Int? = null) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            val t = "Нужно войти. /start"
            if (msgId != null) sender.editText(chatId, msgId, t) else sender.sendText(chatId, t)
            return
        }

        val newCount   = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        val totalCount = leadRepository.countByUserId(user.id)
        val newLabel   = if (newCount > 0) "📬 Новые лиды  •  $newCount" else "📬 Новых лидов нет"

        val text = "📋 Лиды\n\n" +
                "Всего лидов: $totalCount\n" +
                (if (newCount > 0) "🔴 Непросмотренных: $newCount\n" else "") +
                "\nВыберите раздел:"

        val rows = mutableListOf<InlineKeyboardRow>()
        rows.add(row(btn(newLabel, "leads:new")))
        rows.add(row(btn("📄 Все лиды", "leads:all")))
        if (newCount > 0) {
            rows.add(row(btn("✅ Прочитать всё", "leads:readall")))
        }
        rows.add(row(btn("◀️ Главное меню", "menu:back")))

        val kb = InlineKeyboardMarkup(rows)

        if (msgId != null) sender.editText(chatId, msgId, text, kb)
        else sender.sendText(chatId, text, kb)
    }


    fun markAllRead(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        runCatching {
            leadService.markAllRead(user)
        }.onFailure { log.warn("markAllRead failed userId=${user.id}: ${it.message}") }

        showLeadsMenu(chatId, tgUserId, msgId)
    }


    fun showLeadsList(chatId: Long, msgId: Int, tgUserId: Long, page: Int, statusFilter: String?) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val pageDto    = leadService.getLeads(user, statusFilter, page, LEADS_PAGE_SIZE)
        val filterTag  = statusFilter ?: "all"

        if (pageDto.content.isEmpty()) {
            sender.editText(
                chatId, msgId,
                "📭 Лидов нет\n\n" +
                        (if (statusFilter == "NEW") "Новых лидов пока нет.\nДобавьте чаты и ключевые слова."
                        else "Лидов нет.\nДобавьте чаты и ключевые слова для мониторинга."),
                keyboard(row(btn("◀️ Назад", "menu:leads"))),
            )
            return
        }

        val title = when (statusFilter) {
            "NEW"     -> "📬 Новые лиды"
            "VIEWED"  -> "👁 Просмотренные"
            "REPLIED" -> "✅ Отвечено"
            else      -> "📋 Все лиды"
        }

        val sb   = StringBuilder("$title  •  стр. ${page + 1}/${pageDto.totalPages}\n\n")
        val rows = mutableListOf<InlineKeyboardRow>()

        pageDto.content.forEachIndexed { idx, lead ->
            val num        = idx + 1 + page * LEADS_PAGE_SIZE
            val statusIcon = statusIcon(lead.status)
            val aiIcon     = when (lead.aiValid) { true -> " ✨"; false -> " 🚫"; else -> "" }
            val author     = if (lead.authorUsername.isNotBlank()) "@${lead.authorUsername}" else lead.authorName
            val preview    = lead.messageText.take(80).let { if (lead.messageText.length > 80) "$it…" else it }
            val chatLabel  = lead.chatTitle.ifBlank { lead.chatLink }.take(30)

            sb.append("$statusIcon$aiIcon $num. $author\n")
            sb.append("💬 $chatLabel\n")
            sb.append("$preview\n")
            sb.append("🔑 «${lead.matchedKeyword}»\n\n")

            rows.add(row(btn("$statusIcon №$num — Подробнее", "lead:open:${lead.id}")))
        }


        if (pageDto.totalPages > 1) {
            val navBtns = mutableListOf<InlineKeyboardButton>()
            if (page > 0)                        navBtns.add(btn("◀️", "leads:page:${page - 1}:$filterTag"))
            navBtns.add(btn("${page + 1}/${pageDto.totalPages}", "noop"))
            if (page < pageDto.totalPages - 1)   navBtns.add(btn("▶️", "leads:page:${page + 1}:$filterTag"))
            rows.add(InlineKeyboardRow(navBtns))
        }

        rows.add(row(btn("◀️ Назад", "menu:leads")))

        sender.editText(chatId, msgId, sb.toString().trimEnd(), InlineKeyboardMarkup(rows))
    }



    fun showLeadDetail(chatId: Long, msgId: Int, tgUserId: Long, leadId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val lead = leadRepository.findById(leadId).orElse(null)
        if (lead == null || lead.user.id != user.id) {
            sender.editText(chatId, msgId, "❌ Лид не найден.")
            return
        }

        val sub       = lead.subscriptionId?.let { subscriptionRepository.findById(it).orElse(null) }
        val chatLabel = sub?.chatTitle?.ifBlank { sub.chatLink } ?: ""

        val statusLabel = when (lead.status) {
            LeadStatus.NEW     -> "🔴 Новый"
            LeadStatus.VIEWED  -> "🟡 Просмотрен"
            LeadStatus.REPLIED -> "🟢 Отвечено"
            LeadStatus.IGNORED -> "⚫ Архив"
        }
        val aiLine = when (lead.aiValid) {
            true  -> "\n🤖 AI: ✅ одобрил${lead.aiReason?.let { " — $it" } ?: ""}"
            false -> "\n🤖 AI: ❌ отклонил${lead.aiReason?.let { " — $it" } ?: ""}"
            null  -> ""
        }
        val author = buildString {
            append(lead.authorName)
            if (lead.authorUsername.isNotBlank()) append(" (@${lead.authorUsername})")
        }
        val date = lead.foundAt.toLocalDate().toString()
        val time = "%02d:%02d".format(lead.foundAt.hour, lead.foundAt.minute)

        val text = buildString {
            append("📄 Лид #${lead.id}\n\n")
            append("$statusLabel$aiLine\n\n")
            append("👤 Автор: $author\n")
            if (chatLabel.isNotBlank()) append("💬 Чат: $chatLabel\n")
            append("🔑 Ключевое слово: «${lead.matchedKeyword}»\n")
            append("📅 Найден: $date в $time\n\n")
            append("Сообщение:\n${lead.messageText.take(800)}")
            if (lead.messageText.length > 800) append("…")
        }

        val rows = mutableListOf<InlineKeyboardRow>()

        if (lead.messageLink.isNotBlank()) {
            rows.add(row(InlineKeyboardButton.builder()
                .text("🔗 Открыть в Telegram")
                .url(buildTgDeepLink(lead.messageLink))
                .build()))
        }

        when (lead.status) {
            LeadStatus.NEW -> {
                rows.add(row(
                    btn("👁 Просмотрен", "lead:viewed:${lead.id}"),
                    btn("✅ Отвечено",   "lead:replied:${lead.id}"),
                ))
                rows.add(row(btn("🗃 В архив", "lead:ignored:${lead.id}")))
            }
            LeadStatus.VIEWED -> rows.add(row(
                btn("✅ Отвечено", "lead:replied:${lead.id}"),
                btn("🗃 В архив",  "lead:ignored:${lead.id}"),
            ))
            LeadStatus.REPLIED -> rows.add(row(btn("🗃 В архив", "lead:ignored:${lead.id}")))
            else -> {}
        }

        rows.add(row(btn("◀️ К списку", "leads:all")))
        rows.add(row(btn("🏠 Главное меню", "menu:back")))

        sender.editText(chatId, msgId, text, InlineKeyboardMarkup(rows))


        if (lead.status == LeadStatus.NEW) {
            runCatching { leadService.updateLeadStatus(user, leadId, "VIEWED") }
        }
    }



    fun changeLeadStatus(tgUserId: Long, leadId: Long, status: String) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return
        runCatching { leadService.updateLeadStatus(user, leadId, status) }
            .onFailure { log.warn("changeLeadStatus failed leadId=$leadId status=$status: ${it.message}") }
    }



    private fun statusIcon(status: String) = when (status) {
        "NEW"     -> "🔴"
        "VIEWED"  -> "🟡"
        "REPLIED" -> "🟢"
        "IGNORED" -> "⚫"
        else      -> "❓"
    }

    private fun buildTgDeepLink(link: String): String {
        val clean = link
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("t.me/")

        if (clean.startsWith("c/")) {
            val parts  = clean.removePrefix("c/").split("/")
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
}