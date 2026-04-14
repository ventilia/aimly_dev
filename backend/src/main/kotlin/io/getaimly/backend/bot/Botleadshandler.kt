package io.getaimly.backend.bot

import io.getaimly.backend.lead.LeadFeedbackRepository
import io.getaimly.backend.lead.LeadRating
import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.lead.LeadService
import io.getaimly.backend.lead.LeadStatus
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

private const val LEADS_PAGE_SIZE = 5


class BotLeadsHandler(
    private val sender:                 BotSender,
    private val userRepository:         UserRepository,
    private val leadRepository:         LeadRepository,
    private val subscriptionRepository: ChatSubscriptionRepository,
    private val leadService:            LeadService,
    private val feedbackRepo:           LeadFeedbackRepository,
) {

    private val log = LoggerFactory.getLogger(BotLeadsHandler::class.java)

    // ─── Поиск неоцененного блокирующего лида ────────────────────────────────

    /**
     * Возвращает ID лида, который уже был отправлен пользователю (tgNotifiedAt != null),
     * но ещё не получил оценку. Пока этот лид не оценён — новые из очереди не доставляются.
     * Запрос выполняется целиком в БД через NOT EXISTS.
     */
    private fun findUnratedLeadId(userId: Long): Long? =
        leadRepository.findLatestNotifiedWithoutFeedback(
            userId   = userId,
            pageable = PageRequest.of(0, 1),
        ).firstOrNull()

    // ─── Меню лидов ──────────────────────────────────────────────────────────

    fun showLeadsMenu(chatId: Long, tgUserId: Long, msgId: Int? = null) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][LEADS] Не авторизован: chatId=$chatId tgId=$tgUserId")
            val t = "Нужно войти. /start"
            if (msgId != null) sender.editText(chatId, msgId, t) else sender.sendText(chatId, t)
            return
        }

        val unratedLeadId = findUnratedLeadId(user.id)
        val newCount      = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        val totalCount    = leadRepository.countByUserId(user.id)
        val pendingCount  = leadRepository.countPendingNotificationsByUserId(user.id)

        log.info(
            "[BOT][LEADS] Меню лидов: userId=${user.id} email=${user.email} " +
                    "всего=$totalCount новых=$newCount неоцененный=${unratedLeadId ?: "—"} очередь=$pendingCount"
        )

        val sb   = StringBuilder()
        val rows = mutableListOf<InlineKeyboardRow>()

        // ── Если есть неоцененный — вверху показываем мини-карточку лида ─────
        if (unratedLeadId != null) {
            val unratedLead = leadRepository.findById(unratedLeadId).orElse(null)
            val chatName    = unratedLead?.subscriptionId?.let { sid ->
                subscriptionRepository.findById(sid).orElse(null)?.chatTitle?.ifBlank { null }
            }

            sb.append("⚠️ Нужна ваша оценка\n\n")
            if (pendingCount > 0) {
                sb.append("${pendingCount} новых лид${leadSuffix(pendingCount)} ждут в очереди — придут сразу после оценки.\n\n")
            }
            sb.append("📋 Лид #$unratedLeadId")
            if (!chatName.isNullOrBlank()) sb.append("  •  $chatName")
            if (unratedLead != null) {
                sb.append("\n🔑 «${unratedLead.matchedKeyword}»")
                val preview = unratedLead.messageText.take(120)
                sb.append("\n$preview${if (unratedLead.messageText.length > 120) "…" else ""}")
            }
            sb.append("\n\n💡 Оценки помогают ИИ лучше фильтровать лиды под ваш бизнес.\n")
            sb.append("👇 Оцените — и получите следующий лид:")

            rows.add(row(
                btn("👍 Хороший лид", "feedback:good:$unratedLeadId"),
                btn("👎 Не лид",      "feedback:bad:$unratedLeadId"),
            ))
            rows.add(row(btn("📄 Читать полностью", "lead:open:$unratedLeadId")))
            rows.add(row(btn("━━━━━━━━━━━━━━━━━━━━━━━━", "noop")))
            sb.append("\n\n─────────────\n")
        }

        // ── Статистика и навигация ────────────────────────────────────────────
        sb.append("📋 Лиды\n\n")
        sb.append("Всего: $totalCount")
        if (newCount > 0) sb.append("  •  🔴 Новых: $newCount")
        sb.append("\n\nВыберите раздел:")

        val newLabel = if (newCount > 0) "📬 Новые лиды  •  $newCount" else "📬 Новых лидов нет"
        rows.add(row(btn(newLabel, "leads:new")))
        rows.add(row(btn("📋 Все лиды", "leads:all")))
        if (newCount > 0) {
            rows.add(row(btn("✅ Прочитать всё", "leads:readall")))
        }
        rows.add(row(btn("◀️ Главное меню", "menu:back")))

        val kb = InlineKeyboardMarkup(rows)
        if (msgId != null) sender.editText(chatId, msgId, sb.toString(), kb)
        else sender.sendText(chatId, sb.toString(), kb)
    }

    // ─── Прочитать всё ───────────────────────────────────────────────────────

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

    // ─── Список лидов ────────────────────────────────────────────────────────

    fun showLeadsList(chatId: Long, msgId: Int, tgUserId: Long, page: Int, statusFilter: String?) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][LEADS] Не авторизован при просмотре списка: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        val unratedLeadId = findUnratedLeadId(user.id)
        val filterTag     = statusFilter ?: "all"

        log.info(
            "[BOT][LEADS] Список лидов: userId=${user.id} email=${user.email} " +
                    "фильтр=${statusFilter ?: "ALL"} страница=$page неоцененный=${unratedLeadId ?: "—"}"
        )

        val pageDto = leadService.getLeads(user, statusFilter, page, LEADS_PAGE_SIZE)

        if (pageDto.content.isEmpty()) {
            log.info("[BOT][LEADS] Список пуст: userId=${user.id} email=${user.email} фильтр=${statusFilter ?: "ALL"}")
            val rows = mutableListOf<InlineKeyboardRow>()
            if (unratedLeadId != null) {
                rows.add(row(
                    btn("👍 Хороший лид", "feedback:good:$unratedLeadId"),
                    btn("👎 Не лид",      "feedback:bad:$unratedLeadId"),
                ))
                rows.add(row(btn("📄 Посмотреть лид #$unratedLeadId", "lead:open:$unratedLeadId")))
            }
            rows.add(row(btn("◀️ Назад", "menu:leads")))
            sender.editText(
                chatId, msgId,
                "📭 Лидов нет\n\n" + (
                        if (statusFilter == "NEW") "Новых лидов пока нет.\nДобавьте чаты и ключевые слова."
                        else "Лидов нет.\nДобавьте чаты и ключевые слова для мониторинга."
                        ),
                InlineKeyboardMarkup(rows),
            )
            return
        }

        val title = when (statusFilter) {
            "NEW"     -> "📬 Новые лиды"
            "VIEWED"  -> "👁 Просмотренные"
            "REPLIED" -> "✅ Отвечено"
            else      -> "📋 Все лиды"
        }

        val sb   = StringBuilder()
        val rows = mutableListOf<InlineKeyboardRow>()

        // ── Баннер с кнопками оценки поверх списка ───────────────────────────
        if (unratedLeadId != null) {
            val pendingCount = leadRepository.countPendingNotificationsByUserId(user.id)
            sb.append("⚠️ Лид #$unratedLeadId ждёт оценки")
            if (pendingCount > 0) sb.append("  •  ещё $pendingCount в очереди")
            sb.append("\n\n")
            rows.add(row(
                btn("👍 Хороший лид", "feedback:good:$unratedLeadId"),
                btn("👎 Не лид",      "feedback:bad:$unratedLeadId"),
            ))
            rows.add(row(btn("📄 Посмотреть лид #$unratedLeadId", "lead:open:$unratedLeadId")))
            rows.add(row(btn("━━━━━━━━━━━━━━━━━━━━━━━━", "noop")))
        }

        sb.append("$title  •  стр. ${page + 1}/${pageDto.totalPages}\n\n")

        pageDto.content.forEachIndexed { idx, lead ->
            val num          = idx + 1 + page * LEADS_PAGE_SIZE
            // Комбинированная иконка: статус + источник
            val statusIcon   = combinedStatusIcon(lead.status, lead.source)
            val aiIcon       = when (lead.aiValid) { true -> " ✨"; false -> " 🚫"; else -> "" }
            // Оценка показывается для ВСЕХ оцененных лидов независимо от источника
            val feedbackIcon = feedbackIcon(user.id, lead.id)
            // Неоцененный блокирующий лид — предупреждение
            val blockMark    = if (lead.id == unratedLeadId) " ⚠️" else ""
            val author       = if (lead.authorUsername.isNotBlank()) "@${lead.authorUsername}" else lead.authorName
            val preview      = lead.messageText.take(80).let { if (lead.messageText.length > 80) "$it…" else it }
            val chatLabel    = lead.chatTitle.ifBlank { lead.chatLink }.take(30)

            sb.append("$statusIcon$aiIcon$feedbackIcon$blockMark $num. $author\n")
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

    // ─── Карточка лида ───────────────────────────────────────────────────────

    fun showLeadDetail(chatId: Long, msgId: Int, tgUserId: Long, leadId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][LEADS] Не авторизован при открытии лида: chatId=$chatId tgId=$tgUserId leadId=$leadId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        val lead = leadRepository.findById(leadId).orElse(null)
        if (lead == null) {
            log.warn("[BOT][LEADS] Лид не найден: userId=${user.id} leadId=$leadId")
            sender.editText(chatId, msgId, "❌ Лид не найден.")
            return
        }
        if (lead.user.id != user.id) {
            log.warn("[BOT][LEADS] Попытка доступа к чужому лиду: userId=${user.id} leadId=$leadId")
            sender.editText(chatId, msgId, "❌ Лид не найден.")
            return
        }

        val existingFeedback = feedbackRepo.findByUserIdAndLeadId(user.id, leadId)
        val unratedLeadId    = findUnratedLeadId(user.id)
        val isBlockingLead   = (unratedLeadId == leadId)           // этот лид — сам блокирующий
        val anotherBlocks    = unratedLeadId != null && !isBlockingLead

        log.info(
            "[BOT][LEADS] Просмотр лида: userId=${user.id} email=${user.email} leadId=$leadId " +
                    "статус=${lead.status} keyword=\"${lead.matchedKeyword}\" " +
                    "оценка=${existingFeedback?.rating ?: "—"} блокирует=$isBlockingLead другой_блокирует=$anotherBlocks"
        )

        // ── Блокировка: другой лид ждёт оценки, а этот — ещё не доставлен ───
        // Уже оцененные и уже доставленные (tgNotifiedAt != null) лиды открываются свободно.
        val isQueued = lead.tgNotifiedAt == null && existingFeedback == null
        if (anotherBlocks && isQueued) {
            val unratedLead  = leadRepository.findById(unratedLeadId!!).orElse(null)
            val pendingCount = leadRepository.countPendingNotificationsByUserId(user.id)

            val blockText = buildString {
                append("🔒 Лид #$leadId в очереди\n\n")
                append("Чтобы получить его, сначала оцените лид #$unratedLeadId.\n")
                append("После оценки он придёт автоматически.\n\n")
                if (pendingCount > 1) {
                    append("В очереди ещё ${pendingCount - 1} лид${leadSuffix(pendingCount - 1)}.\n\n")
                }
                append("💡 Оценки учит ИИ фильтровать лиды точнее — это важно.")
                if (unratedLead != null) {
                    val preview = unratedLead.messageText.take(100)
                    append("\n\n─────────────\n📋 Лид #$unratedLeadId (ждёт оценки):\n")
                    append("🔑 «${unratedLead.matchedKeyword}»\n")
                    append("$preview${if (unratedLead.messageText.length > 100) "…" else ""}")
                }
            }

            val rows = mutableListOf<InlineKeyboardRow>()
            rows.add(row(
                btn("👍 Хороший лид", "feedback:good:$unratedLeadId"),
                btn("👎 Не лид",      "feedback:bad:$unratedLeadId"),
            ))
            rows.add(row(btn("📄 Читать лид #$unratedLeadId", "lead:open:$unratedLeadId")))
            rows.add(row(btn("◀️ Все лиды", "leads:all")))

            sender.editText(chatId, msgId, blockText, InlineKeyboardMarkup(rows))
            return
        }

        // ── Обычная карточка лида ─────────────────────────────────────────────
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
        val feedbackLine = when (existingFeedback?.rating) {
            LeadRating.GOOD -> "\n⭐ Ваша оценка: 👍 Хороший лид"
            LeadRating.BAD  -> "\n⭐ Ваша оценка: 👎 Не лид"
            null            -> ""
        }
        val author = buildString {
            append(lead.authorName)
            if (lead.authorUsername.isNotBlank()) append(" (@${lead.authorUsername})")
        }

        val displayDate = lead.messageDate ?: lead.foundAt
        val date = displayDate.toLocalDate().toString()
        val time = "%02d:%02d".format(displayDate.hour, displayDate.minute)

        val sourceLabel = when (lead.source.name) {
            "MANUAL_EXPORT" -> "📁 экспорт файла"
            else            -> "мониторинг"
        }

        val text = buildString {
            append("📄 Лид #${lead.id}\n\n")
            append("$statusLabel$aiLine$feedbackLine\n")
            append("Источник: $sourceLabel\n\n")
            append("👤 Автор: $author\n")
            if (chatLabel.isNotBlank()) append("💬 Чат: $chatLabel\n")
            append("🔑 Ключевое слово: «${lead.matchedKeyword}»\n")
            append("📅 Дата: $date в $time\n\n")
            append("Сообщение:\n${lead.messageText.take(800)}")
            if (lead.messageText.length > 800) append("…")
        }

        val rows = mutableListOf<InlineKeyboardRow>()

        // ── 1. Оценка: первичная или смена ────────────────────────────────────
        when {
            // Лид ещё не оценён и уже был доставлен (или сам является блокирующим)
            existingFeedback == null && (lead.tgNotifiedAt != null || isBlockingLead) -> {
                rows.add(row(
                    btn("👍 Хороший лид", "feedback:good:$leadId"),
                    btn("👎 Не лид",      "feedback:bad:$leadId"),
                ))
            }
            // Лид уже оценён — кнопка смены оценки
            existingFeedback != null -> {
                val (changeLabel, changeCb) = when (existingFeedback.rating) {
                    LeadRating.GOOD -> "Изменить → 👎 Не лид"  to "feedback:bad:$leadId"
                    LeadRating.BAD  -> "Изменить → 👍 Хороший лид" to "feedback:good:$leadId"
                }
                rows.add(row(btn(changeLabel, changeCb)))
            }
            // Другой лид ожидает оценки (fallback для уже VIEWED лидов)
            anotherBlocks -> {
                rows.add(row(btn("⚠️ Оценить лид #$unratedLeadId", "lead:open:$unratedLeadId")))
            }
        }

        // ── 2. Открыть оригинал в Telegram ───────────────────────────────────
        if (lead.messageLink.isNotBlank()) {
            rows.add(row(InlineKeyboardButton.builder()
                .text("🔗 Открыть в Telegram")
                .url(buildTgDeepLink(lead.messageLink))
                .build()))
        }

        // ── 3. Смена статуса ─────────────────────────────────────────────────
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

        // ── 4. Навигация ──────────────────────────────────────────────────────
        rows.add(row(btn("◀️ К списку", "leads:all")))
        rows.add(row(btn("🏠 Главное меню", "menu:back")))

        sender.editText(chatId, msgId, text, InlineKeyboardMarkup(rows))

        // Автоматически помечаем NEW как VIEWED
        if (lead.status == LeadStatus.NEW) {
            runCatching {
                leadService.updateLeadStatus(user, leadId, "VIEWED")
                log.info("[BOT][LEADS] Лид авто-помечен VIEWED: userId=${user.id} leadId=$leadId")
            }.onFailure {
                log.warn("[BOT][LEADS] ❌ Ошибка авто-VIEWED: userId=${user.id} leadId=$leadId причина=${it.message}")
            }
        }
    }

    // ─── Смена статуса ───────────────────────────────────────────────────────

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
            log.warn("[BOT][LEADS] ❌ Ошибка смены статуса: userId=${user.id} leadId=$leadId status=$status причина=${it.message}")
        }
    }

    // ─── Приватные хелперы ────────────────────────────────────────────────────

    /**
     * Иконка оценки для списка лидов.
     * Показывается для всех оцененных лидов независимо от источника и tgNotifiedAt.
     */
    private fun feedbackIcon(userId: Long, leadId: Long): String =
        runCatching {
            when (feedbackRepo.findByUserIdAndLeadId(userId, leadId)?.rating) {
                LeadRating.GOOD -> " 👍"
                LeadRating.BAD  -> " 👎"
                null            -> ""
            }
        }.getOrDefault("")

    /**
     * Комбинированная иконка: учитывает и статус лида, и его источник.
     * Экспортные лиды отображаются иконкой папки, чтобы отличать их от живых.
     */
    private fun combinedStatusIcon(status: String, source: String): String =
        if (source == "MANUAL_EXPORT") {
            when (status) {
                "NEW"     -> "📁"
                "VIEWED"  -> "📂"
                "REPLIED" -> "📂✅"
                "IGNORED" -> "📂⚫"
                else      -> "📁"
            }
        } else {
            when (status) {
                "NEW"     -> "🔴"
                "VIEWED"  -> "🟡"
                "REPLIED" -> "🟢"
                "IGNORED" -> "⚫"
                else      -> "❓"
            }
        }

    /** Склонение слова "лид": 1 лид, 2 лида, 5 лидов. */
    private fun leadSuffix(count: Long): String = when {
        count % 100 in 11..19 -> " лидов"
        count % 10 == 1L      -> " лид"
        count % 10 in 2..4    -> " лида"
        else                  -> " лидов"
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