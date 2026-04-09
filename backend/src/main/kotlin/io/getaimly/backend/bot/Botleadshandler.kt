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
            log.warn("[BOT][LEADS] Не авторизован при открытии меню лидов: chatId=$chatId tgId=$tgUserId")
            val t = "Нужно войти. /start"
            if (msgId != null) sender.editText(chatId, msgId, t) else sender.sendText(chatId, t)
            return
        }

        val newCount   = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        val totalCount = leadRepository.countByUserId(user.id)
        log.info("[BOT][LEADS] Меню лидов: userId=${user.id} email=${user.email} всего=$totalCount новых=$newCount")

        val newLabel = if (newCount > 0) "📬 Новые лиды  •  $newCount" else "📬 Новых лидов нет"

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
        if (user == null) {
            log.warn("[BOT][LEADS] Не авторизован при «прочитать всё»: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        val countBefore = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        log.info("[BOT][LEADS] «Прочитать всё»: userId=${user.id} email=${user.email} будет_помечено=$countBefore")

        runCatching {
            leadService.markAllRead(user)
            log.info("[BOT][LEADS] ✅ Все лиды помечены прочитанными: userId=${user.id} email=${user.email} помечено=$countBefore")
        }.onFailure {
            log.warn("[BOT][LEADS] ❌ Ошибка «прочитать всё»: userId=${user.id} email=${user.email} причина=${it.message}")
        }

        showLeadsMenu(chatId, tgUserId, msgId)
    }


    fun showLeadsList(chatId: Long, msgId: Int, tgUserId: Long, page: Int, statusFilter: String?) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][LEADS] Не авторизован при просмотре списка: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        log.info("[BOT][LEADS] Список лидов: userId=${user.id} email=${user.email} фильтр=${statusFilter ?: "ALL"} страница=$page")

        val pageDto   = leadService.getLeads(user, statusFilter, page, LEADS_PAGE_SIZE)
        val filterTag = statusFilter ?: "all"

        if (pageDto.content.isEmpty()) {
            log.info("[BOT][LEADS] Список пуст: userId=${user.id} email=${user.email} фильтр=${statusFilter ?: "ALL"}")
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
            val sourceTag  = sourceTag(lead.source)
            val author     = if (lead.authorUsername.isNotBlank()) "@${lead.authorUsername}" else lead.authorName
            val preview    = lead.messageText.take(80).let { if (lead.messageText.length > 80) "$it…" else it }
            val chatLabel  = lead.chatTitle.ifBlank { lead.chatLink }.take(30)

            sb.append("$statusIcon$aiIcon $num. $author  $sourceTag\n")
            if (chatLabel.isNotBlank()) sb.append("💬 $chatLabel\n")
            sb.append("$preview\n")
            sb.append("🔑 «${lead.matchedKeyword}»\n\n")

            rows.add(row(btn("$statusIcon №$num — Подробнее", "lead:open:${lead.id}")))
        }

        if (pageDto.totalPages > 1) {
            val navBtns = mutableListOf<InlineKeyboardButton>()
            if (page > 0)                       navBtns.add(btn("◀️", "leads:page:${page - 1}:$filterTag"))
            navBtns.add(btn("${page + 1}/${pageDto.totalPages}", "noop"))
            if (page < pageDto.totalPages - 1)  navBtns.add(btn("▶️", "leads:page:${page + 1}:$filterTag"))
            rows.add(InlineKeyboardRow(navBtns))
        }

        rows.add(row(btn("◀️ Назад", "menu:leads")))

        sender.editText(chatId, msgId, sb.toString().trimEnd(), InlineKeyboardMarkup(rows))
    }


    fun showLeadDetail(chatId: Long, msgId: Int, tgUserId: Long, leadId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][LEADS] Не авторизован при открытии лида: chatId=$chatId tgId=$tgUserId leadId=$leadId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        val lead = leadRepository.findById(leadId).orElse(null)
        if (lead == null) {
            log.warn("[BOT][LEADS] Лид не найден: userId=${user.id} email=${user.email} leadId=$leadId")
            sender.editText(chatId, msgId, "❌ Лид не найден.")
            return
        }
        if (lead.user.id != user.id) {
            log.warn("[BOT][LEADS] Попытка доступа к чужому лиду: userId=${user.id} email=${user.email} leadId=$leadId владелец=${lead.user.id}")
            sender.editText(chatId, msgId, "❌ Лид не найден.")
            return
        }

        log.info("[BOT][LEADS] Просмотр лида: userId=${user.id} email=${user.email} leadId=$leadId статус=${lead.status} keyword=\"${lead.matchedKeyword}\" автор=@${lead.authorUsername.ifBlank { lead.authorName }}")

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

        // Для экспорта показываем реальную дату сообщения (messageDate),
        // для мониторинга — дату обнаружения (foundAt). Они совпадают для LIVE.
        val displayDate = lead.messageDate ?: lead.foundAt
        val date = displayDate.toLocalDate().toString()
        val time = "%02d:%02d".format(displayDate.hour, displayDate.minute)

        val sourceLabel = when (lead.source.name) {
            "MANUAL_EXPORT" -> "экспорт файла"
            else            -> "мониторинг"
        }

        val text = buildString {
            append("📄 Лид #${lead.id}\n\n")
            append("$statusLabel$aiLine\n")
            append("Источник: $sourceLabel\n\n")
            append("👤 Автор: $author\n")
            if (chatLabel.isNotBlank()) append("💬 Чат: $chatLabel\n")
            append("🔑 Ключевое слово: «${lead.matchedKeyword}»\n")
            append("📅 Дата: $date в $time\n\n")
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

        // Автоматически помечаем как просмотренный
        if (lead.status == LeadStatus.NEW) {
            runCatching {
                leadService.updateLeadStatus(user, leadId, "VIEWED")
                log.info("[BOT][LEADS] Лид авто-помечен VIEWED: userId=${user.id} email=${user.email} leadId=$leadId")
            }.onFailure {
                log.warn("[BOT][LEADS] ❌ Ошибка авто-VIEWED: userId=${user.id} email=${user.email} leadId=$leadId причина=${it.message}")
            }
        }
    }


    fun changeLeadStatus(tgUserId: Long, leadId: Long, status: String) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][LEADS] Не авторизован при смене статуса: tgId=$tgUserId leadId=$leadId status=$status")
            return
        }

        val oldStatus = leadRepository.findById(leadId).orElse(null)?.status?.name ?: "?"
        log.info("[BOT][LEADS] Смена статуса лида: userId=${user.id} email=${user.email} leadId=$leadId $oldStatus → $status")

        runCatching {
            leadService.updateLeadStatus(user, leadId, status)
            log.info("[BOT][LEADS] ✅ Статус изменён: userId=${user.id} email=${user.email} leadId=$leadId → $status")
        }.onFailure {
            log.warn("[BOT][LEADS] ❌ Ошибка смены статуса: userId=${user.id} email=${user.email} leadId=$leadId status=$status причина=${it.message}")
        }
    }


    private fun sourceTag(source: String) = when (source) {
        "MANUAL_EXPORT" -> "[экспорт]"
        else            -> "[монитор]"
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