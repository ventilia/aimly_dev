package io.getaimly.backend.bot

import io.getaimly.backend.lead.ChatSearchResult
import io.getaimly.backend.lead.ChatSearchService
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.LeadService
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val SEARCH_PAGE_SIZE = 3

class BotChatSearchHandler(
    private val sender: BotSender,
    private val sessions: ConcurrentHashMap<Long, UserSession>,
    private val userRepository: UserRepository,
    private val subscriptionRepository: ChatSubscriptionRepository,
    private val keywordRepository: KeywordRepository,
    private val leadService: LeadService,
    private val chatSearchService: ChatSearchService,
) {
    private val log      = LoggerFactory.getLogger(BotChatSearchHandler::class.java)
    private val worker   = Executors.newCachedThreadPool { r ->
        Thread(r, "bot-chat-search-worker").also { it.isDaemon = true }
    }

    // ─── Точка входа: показываем экран поиска ────────────────────────────────

    fun showSearchScreen(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val plan      = user.subscriptionPlan
        val status    = user.subscriptionStatus
        val hasAccess = plan in setOf("MINIMUM", "START") || status == "TRIAL"

        if (!hasAccess) {
            sender.editText(
                chatId, msgId,
                "🔒 *AI-поиск чатов*\n\n" +
                        "Поиск подходящих Telegram-чатов по вашей нише доступен на тарифе *МИНИМУМ* и выше.\n\n" +
                        "AI автоматически подберёт чаты, где ваша целевая аудитория активно общается.",
                keyboard(
                    row(urlBtn("💳 Выбрать тариф", BotAuthHandler.SITE_URL + "/checkout")),
                    row(btn("◀️ Назад к чатам", "menu:chats")),
                ),
                parseMarkdown = true,
            )
            return
        }

        // Есть ли ключевые слова для быстрого поиска?
        val keywords   = keywordRepository.findByUserIdAndIsActiveTrue(user.id)
        val hasContext = !user.businessContext.isNullOrBlank()

        val rows = mutableListOf<InlineKeyboardRow>()

        if (keywords.isNotEmpty()) {
            val kwPreview = keywords.take(3).joinToString(", ") { "«${it.keyword.take(15)}»" }
            rows.add(row(btn("🔑 По ключевым словам ($kwPreview…)", "csearch:by_keywords")))
        }
        if (hasContext) {
            rows.add(row(btn("🎯 По AI-профилю бизнеса", "csearch:by_context")))
        }
        rows.add(row(btn("✏️ Ввести запрос вручную", "csearch:manual")))
        rows.add(row(btn("◀️ Назад к чатам", "menu:chats")))

        sender.editText(
            chatId, msgId,
            "🔍 *AI-поиск чатов*\n\n" +
                    "Найдём Telegram-чаты, где ваша аудитория общается и ищет услуги.\n\n" +
                    "Выберите источник запроса:",
            InlineKeyboardMarkup(rows),
            parseMarkdown = true,
        )
    }

    // ─── Поиск по ключевым словам пользователя ───────────────────────────────

    fun searchByKeywords(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val keywords = keywordRepository.findByUserIdAndIsActiveTrue(user.id)
        if (keywords.isEmpty()) {
            sender.editText(
                chatId, msgId,
                "⚠️ У вас нет ключевых слов.\n\nДобавьте их в разделе «🔍 Ключевые слова», затем вернитесь сюда.",
                keyboard(
                    row(btn("🔍 Ключевые слова", "menu:keywords")),
                    row(btn("◀️ Назад",            "csearch:start")),
                ),
            )
            return
        }

        val query = keywords.take(5).joinToString(", ") { it.keyword }
        runSearch(chatId, msgId, tgUserId, query)
    }

    // ─── Поиск по AI-профилю бизнеса ─────────────────────────────────────────

    fun searchByContext(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val context = user.businessContext
        if (context.isNullOrBlank()) {
            sender.editText(
                chatId, msgId,
                "⚠️ AI-профиль бизнеса не заполнен.\n\nОпишите свой бизнес в «Профиль → AI-персонализация».",
                keyboard(
                    row(btn("👤 Перейти в профиль", "menu:profile")),
                    row(btn("◀️ Назад",               "csearch:start")),
                ),
            )
            return
        }

        runSearch(chatId, msgId, tgUserId, context)
    }

    // ─── Ручной ввод запроса ─────────────────────────────────────────────────

    fun startManualSearch(chatId: Long, msgId: Int) {
        sessions[chatId] = UserSession(step = BotStep.WAITING_CHAT_SEARCH_QUERY, msgId = msgId)
        sender.editText(
            chatId, msgId,
            "🔍 *Поиск чатов*\n\n" +
                    "Опишите тематику чатов, которые вам нужны.\n\n" +
                    "💡 Примеры:\n" +
                    "• `фриланс дизайн`\n" +
                    "• `поиск разработчика React`\n" +
                    "• `ищу SMM специалиста`\n" +
                    "• `digital агентство Москва`",
            keyboard(row(btn("❌ Отмена", "csearch:start"))),
            parseMarkdown = true,
        )
    }

    fun handleSearchQueryInput(chatId: Long, text: String, from: org.telegram.telegrambots.meta.api.objects.User) {
        val session = sessions.remove(chatId) ?: return
        val msgId   = session.msgId

        val query = text.trim()
        if (query.isBlank()) {
            sender.sendText(chatId, "⚠️ Запрос не может быть пустым. Попробуйте снова:")
            sessions[chatId] = UserSession(step = BotStep.WAITING_CHAT_SEARCH_QUERY, msgId = msgId)
            return
        }
        if (query.length > 300) {
            sender.sendText(chatId, "⚠️ Запрос слишком длинный (макс. 300 символов).")
            sessions[chatId] = UserSession(step = BotStep.WAITING_CHAT_SEARCH_QUERY, msgId = msgId)
            return
        }

        runSearch(chatId, msgId, from.id, query)
    }

    // ─── Основная логика поиска ───────────────────────────────────────────────

    private fun runSearch(chatId: Long, msgId: Int, tgUserId: Long, query: String) {
        sender.editText(
            chatId, msgId,
            "🔍 *AI ищет чаты…*\n\n" +
                    "Анализирую запрос, подбираю подходящие Telegram-чаты и фильтрую нерелевантные.\n\n" +
                    "_Обычно это занимает 5–20 секунд._",
            parseMarkdown = true,
        )

        worker.submit {
            runCatching {
                chatSearchService.search(query)
            }.onSuccess { resp ->
                if (resp.results.isEmpty()) {
                    sender.editText(
                        chatId, msgId,
                        "😕 *Чаты не найдены*\n\n" +
                                "По запросу «${query.take(60).md()}» не удалось найти подходящих чатов.\n\n" +
                                "Попробуйте уточнить или изменить запрос.",
                        keyboard(
                            row(btn("🔄 Новый поиск", "csearch:manual")),
                            row(btn("◀️ Назад",        "menu:chats")),
                        ),
                        parseMarkdown = true,
                    )
                    return@submit
                }

                // Сохраняем результаты в сессию
                sessions[chatId] = UserSession(
                    step               = BotStep.WAITING_CHAT_SEARCH_QUERY, // используется как маркер активной сессии
                    msgId              = msgId,
                    chatSearchResults  = resp.results,
                    chatSearchQuery    = query,
                    chatSearchPage     = 0,
                    chatSearchAdded    = mutableSetOf(),
                    chatSearchDismissed = mutableSetOf(),
                )

                // Убираем шаг — результаты уже загружены, текстовый ввод не нужен
                sessions[chatId]?.step = BotStep.WAITING_AI_KEYWORD_CONFIRM // любой не-текстовый шаг

                showResults(chatId, msgId, tgUserId, 0)

            }.onFailure { e ->
                log.warn("BotChatSearchHandler: search failed for query='$query': ${e.message}")
                sender.editText(
                    chatId, msgId,
                    "❌ *Ошибка поиска*\n\n${e.message ?: "Попробуйте позже."}",
                    keyboard(
                        row(btn("🔄 Повторить", "csearch:manual")),
                        row(btn("◀️ Назад",      "menu:chats")),
                    ),
                    parseMarkdown = true,
                )
            }
        }
    }

    // ─── Отображение результатов ──────────────────────────────────────────────

    fun showResults(chatId: Long, msgId: Int, tgUserId: Long, page: Int) {
        val user    = userRepository.findByTelegramId(tgUserId).orElse(null)
        val session = sessions[chatId]

        if (session == null || session.chatSearchResults.isEmpty()) {
            sender.editText(
                chatId, msgId,
                "Результаты поиска устарели. Выполните новый поиск.",
                keyboard(row(btn("🔍 Поиск", "csearch:start"))),
            )
            return
        }

        val results = session.chatSearchResults
        val query   = session.chatSearchQuery.take(50)

        // Считаем «активные» результаты (не скрытые)
        val visible   = results.indices.filter { it !in session.chatSearchDismissed }
        val totalPages = (visible.size + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE
        val safePage   = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        session.chatSearchPage = safePage

        val pageIndices = visible.drop(safePage * SEARCH_PAGE_SIZE).take(SEARCH_PAGE_SIZE)

        if (pageIndices.isEmpty()) {
            val addedCount = session.chatSearchAdded.size
            sender.editText(
                chatId, msgId,
                if (addedCount > 0)
                    "✅ *Готово!*\n\nДобавлено чатов: $addedCount\nВсе результаты просмотрены."
                else
                    "Все результаты скрыты. Выполните новый поиск.",
                keyboard(
                    row(btn("🔄 Новый поиск", "csearch:manual")),
                    row(btn("◀️ К чатам",     "menu:chats")),
                ),
                parseMarkdown = true,
            )
            sessions.remove(chatId)
            return
        }

        val remaining = visible.count { it !in session.chatSearchAdded }
        val sb = StringBuilder()
        sb.append("🔍 *Результаты поиска*")
        if (query.isNotBlank()) sb.append("\n_По запросу: «${query.md()}»_")
        sb.append("\n\n")
        if (totalPages > 1) sb.append("Стр. ${safePage + 1}/$totalPages  •  ")
        sb.append("Найдено: ${visible.size}  •  Не добавлено: $remaining\n\n")

        pageIndices.forEach { idx ->
            val r       = results[idx]
            val status  = when (idx) {
                in session.chatSearchAdded -> "✅"
                else                       -> if (r.peerType == "chat") "💬" else "📢"
            }
            val members = if (r.participantsCount > 0) " · ${formatCount(r.participantsCount)} уч." else ""

            // Экранируем все динамические строки.
            // Описание выводим как обычный текст (без italic), чтобы
            // спецсимволы внутри не ломали Markdown v1 парсер Telegram.
            val safeTitle = r.title.take(40).md()
            val safeDesc  = r.description?.take(100)?.md()
            // username уже содержит @ — экранируем только служебные символы
            val safeUser  = r.username?.replace("_", "\\_")?.replace("*", "\\*")

            sb.append("$status *$safeTitle*$members\n")
            if (!safeDesc.isNullOrBlank()) {
                sb.append("  $safeDesc\n")
            }
            if (safeUser != null) sb.append("  $safeUser\n")
            sb.append("\n")
        }

        val rows = mutableListOf<InlineKeyboardRow>()

        // Кнопки для каждого результата
        pageIndices.forEach { idx ->
            val r         = results[idx]
            val shortTitle = r.title.take(25)
            val isAdded   = idx in session.chatSearchAdded

            val addBtn = if (isAdded)
                btn("✅ $shortTitle", "noop")
            else
                btn("➕ $shortTitle", "csearch:add:$idx")

            val skipBtn = btn("✖", "csearch:skip:$idx")

            rows.add(
                if (isAdded) row(addBtn)
                else         row(addBtn, skipBtn)
            )
        }

        // Навигация
        if (totalPages > 1) {
            val navBtns = mutableListOf<InlineKeyboardButton>()
            if (safePage > 0)              navBtns.add(btn("◀️", "csearch:page:${safePage - 1}"))
            navBtns.add(btn("${safePage + 1}/$totalPages", "noop"))
            if (safePage < totalPages - 1) navBtns.add(btn("▶️", "csearch:page:${safePage + 1}"))
            rows.add(InlineKeyboardRow(navBtns))
        }

        // Массовые действия
        val notAddedVisible = pageIndices.filter { it !in session.chatSearchAdded }
        if (notAddedVisible.size > 1) {
            rows.add(row(btn("✅ Добавить все на странице", "csearch:add_page:$safePage")))
        }
        if (visible.count { it !in session.chatSearchAdded } > SEARCH_PAGE_SIZE) {
            rows.add(row(btn("✅ Добавить все (${remaining})", "csearch:add_all")))
        }

        rows.add(row(
            btn("🔄 Новый поиск", "csearch:manual"),
            btn("◀️ К чатам",     "menu:chats"),
        ))

        sender.editText(chatId, msgId, sb.toString(), InlineKeyboardMarkup(rows), parseMarkdown = true)
    }

    // ─── Добавить один чат из результатов ────────────────────────────────────

    fun addFromSearch(chatId: Long, msgId: Int, tgUserId: Long, idx: Int) {
        val user    = userRepository.findByTelegramId(tgUserId).orElse(null)
        val session = sessions[chatId]

        if (user == null || session == null) {
            sender.editText(chatId, msgId, "Сессия устарела. /start"); return
        }
        if (idx < 0 || idx >= session.chatSearchResults.size) return
        if (idx in session.chatSearchAdded) {
            showResults(chatId, msgId, tgUserId, session.chatSearchPage)
            return
        }

        val result = session.chatSearchResults[idx]

        runCatching { leadService.addSubscription(user, result.link) }
            .onSuccess {
                session.chatSearchAdded.add(idx)
                log.info("BotChatSearch: userId=${user.id} добавил чат '${result.title}' link=${result.link}")
                showResults(chatId, msgId, tgUserId, session.chatSearchPage)
            }
            .onFailure { e ->
                log.warn("BotChatSearch: addSubscription failed idx=$idx link=${result.link}: ${e.message}")
                // Если уже подписан — отмечаем как добавленный, показываем дальше
                if (e.message?.contains("уже подписаны") == true || e.message?.contains("already") == true) {
                    session.chatSearchAdded.add(idx)
                }
                sender.editText(
                    chatId, msgId,
                    "❌ Не удалось добавить «${result.title.take(40)}»:\n${e.message}\n\nПродолжаем...",
                    keyboard(row(btn("◀️ К результатам", "csearch:page:${session.chatSearchPage}"))),
                )
            }
    }

    // ─── Добавить все на странице ─────────────────────────────────────────────

    fun addPage(chatId: Long, msgId: Int, tgUserId: Long, page: Int) {
        val user    = userRepository.findByTelegramId(tgUserId).orElse(null)
        val session = sessions[chatId]

        if (user == null || session == null) return

        val visible     = session.chatSearchResults.indices.filter { it !in session.chatSearchDismissed }
        val pageIndices = visible.drop(page * SEARCH_PAGE_SIZE).take(SEARCH_PAGE_SIZE)
        val toAdd       = pageIndices.filter { it !in session.chatSearchAdded }

        var added  = 0
        var errors = 0
        toAdd.forEach { idx ->
            val link = session.chatSearchResults[idx].link
            runCatching { leadService.addSubscription(user, link) }
                .onSuccess { session.chatSearchAdded.add(idx); added++ }
                .onFailure { e ->
                    if (e.message?.contains("уже подписаны") == true) {
                        session.chatSearchAdded.add(idx); added++
                    } else {
                        errors++
                        log.warn("addPage: idx=$idx link=$link error: ${e.message}")
                    }
                }
        }

        log.info("BotChatSearch: addPage page=$page added=$added errors=$errors userId=${user.id}")
        showResults(chatId, msgId, tgUserId, page)
    }

    // ─── Добавить все чаты из результатов ────────────────────────────────────

    fun addAll(chatId: Long, msgId: Int, tgUserId: Long) {
        val user    = userRepository.findByTelegramId(tgUserId).orElse(null)
        val session = sessions[chatId]

        if (user == null || session == null) return

        val toAdd = session.chatSearchResults.indices
            .filter { it !in session.chatSearchDismissed && it !in session.chatSearchAdded }

        if (toAdd.isEmpty()) {
            showResults(chatId, msgId, tgUserId, session.chatSearchPage)
            return
        }

        sender.editText(
            chatId, msgId,
            "⏳ Добавляем ${toAdd.size} чатов…",
        )

        worker.submit {
            var added  = 0
            var errors = 0
            toAdd.forEach { idx ->
                val link = session.chatSearchResults[idx].link
                runCatching { leadService.addSubscription(user, link) }
                    .onSuccess { session.chatSearchAdded.add(idx); added++ }
                    .onFailure { e ->
                        if (e.message?.contains("уже подписаны") == true) {
                            session.chatSearchAdded.add(idx); added++
                        } else {
                            errors++
                        }
                    }
                Thread.sleep(500) // небольшая пауза между запросами к юзерботу
            }

            log.info("BotChatSearch: addAll added=$added errors=$errors userId=${user.id}")

            val text = buildString {
                append("✅ *Готово!*\n\n")
                append("Добавлено чатов: $added\n")
                if (errors > 0) append("Ошибок: $errors\n")
                append("\nUserbot начнёт мониторинг в ближайшие минуты.")
            }
            sessions.remove(chatId)
            sender.editText(
                chatId, msgId, text,
                keyboard(row(btn("◀️ К чатам", "menu:chats"))),
                parseMarkdown = true,
            )
        }
    }

    // ─── Скрыть один результат ────────────────────────────────────────────────

    fun dismissResult(chatId: Long, msgId: Int, tgUserId: Long, idx: Int) {
        val session = sessions[chatId] ?: return
        session.chatSearchDismissed.add(idx)

        // Если на текущей странице ничего не осталось — переходим на предыдущую
        val visible     = session.chatSearchResults.indices.filter { it !in session.chatSearchDismissed }
        val totalPages  = ((visible.size + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE).coerceAtLeast(1)
        val currentPage = session.chatSearchPage.coerceAtMost(totalPages - 1)

        showResults(chatId, msgId, tgUserId, currentPage)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun formatCount(n: Int): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
        n >= 1_000     -> "${n / 1_000}.${(n % 1_000) / 100}K"
        else           -> n.toString()
    }
}