package io.getaimly.backend.bot

import io.getaimly.backend.lead.ChatSearchService
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.lead.LeadService
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
    private val log = LoggerFactory.getLogger(BotChatSearchHandler::class.java)

    private val worker = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "bot-chat-search-worker").also { it.isDaemon = true }
    }

    // ─── Экран выбора режима поиска ───────────────────────────────────────────

    fun showSearchScreen(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val plan      = user.subscriptionPlan
        val status    = user.subscriptionStatus
        val hasAccess = plan in setOf("MINIMUM", "START") || status == "TRIAL"

        if (!hasAccess) {
            sender.editText(
                chatId, msgId,
                "🔒 AI-поиск чатов\n\n" +
                    "Поиск подходящих Telegram-чатов доступен на тарифе МИНИМУМ и выше.\n\n" +
                    "AI автоматически подберёт чаты, где ваша целевая аудитория активно общается.",
                keyboard(
                    row(urlBtn("💳 Выбрать тариф", BotAuthHandler.SITE_URL + "/checkout")),
                    row(btn("◀️ Назад к чатам", "menu:chats")),
                ),
            )
            return
        }

        val keywords   = keywordRepository.findByUserIdAndIsActiveTrue(user.id)
        val hasContext = !user.businessContext.isNullOrBlank()

        val rows = mutableListOf<InlineKeyboardRow>()
        if (keywords.isNotEmpty()) {
            val kwPreview = keywords.take(2).joinToString(", ") { "«${it.keyword.take(15)}»" }
            rows.add(row(btn("🔑 По ключевым словам ($kwPreview)", "csearch:by_keywords")))
        }
        if (hasContext) {
            rows.add(row(btn("🎯 По AI-профилю бизнеса", "csearch:by_context")))
        }
        rows.add(row(btn("✏️ Ввести запрос вручную", "csearch:manual")))
        rows.add(row(btn("◀️ Назад к чатам", "menu:chats")))

        sender.editText(
            chatId, msgId,
            "🔍 AI-поиск чатов\n\n" +
                "Найдём Telegram-чаты, где ваша аудитория общается и ищет услуги.\n\n" +
                "Выберите источник запроса:",
            InlineKeyboardMarkup(rows),
        )
    }

    // ─── Поиск по ключевым словам ─────────────────────────────────────────────

    fun searchByKeywords(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val keywords = keywordRepository.findByUserIdAndIsActiveTrue(user.id)
        if (keywords.isEmpty()) {
            sender.editText(
                chatId, msgId,
                "⚠️ У вас нет ключевых слов.\n\nДобавьте их в «Ключевые слова», затем вернитесь сюда.",
                keyboard(
                    row(btn("🔍 Ключевые слова", "menu:keywords")),
                    row(btn("◀️ Назад", "csearch:start")),
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
                "⚠️ AI-профиль бизнеса не заполнен.\n\nОпишите свой бизнес в «Профиль > AI-персонализация».",
                keyboard(
                    row(btn("👤 Перейти в профиль", "menu:profile")),
                    row(btn("◀️ Назад", "csearch:start")),
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
            "🔍 Поиск чатов\n\n" +
                "Опишите тематику чатов, которые вам нужны.\n\n" +
                "Примеры:\n" +
                "• фриланс дизайн\n" +
                "• поиск разработчика React\n" +
                "• SMM специалист Москва",
            keyboard(row(btn("❌ Отмена", "csearch:start"))),
        )
    }

    fun handleSearchQueryInput(
        chatId: Long,
        text: String,
        from: org.telegram.telegrambots.meta.api.objects.User,
    ) {
        val session = sessions.remove(chatId) ?: return
        val msgId   = session.msgId

        val query = text.trim()
        if (query.isBlank()) {
            sender.sendText(chatId, "⚠️ Запрос не может быть пустым. Отправьте текст:")
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

    /**
     * Запускает поиск в фоновом потоке.
     *
     * КРИТИЧЕСКИ ВАЖНО: используем обычный try/catch, а НЕ runCatching-цепочку.
     * Kotlin runCatching { }.onSuccess { } — исключения из onSuccess{} НЕ
     * перехватываются onFailure{} и тихо проглатываются thread pool executor'ом.
     * Это была причина, по которой результаты никогда не приходили в бот.
     */
    private fun runSearch(chatId: Long, loadingMsgId: Int, tgUserId: Long, query: String) {
        // Обновляем сообщение: показываем индикатор загрузки
        sender.editText(
            chatId, loadingMsgId,
            "⏳ AI ищет чаты...\n\n" +
                "Анализирую запрос, подбираю подходящие Telegram-чаты.\n" +
                "Обычно это занимает 5-20 секунд.",
        )

        worker.submit {
            try {
                log.info("BotChatSearch: поиск chatId=$chatId query='${query.take(60)}'")

                val resp = chatSearchService.search(query)

                log.info("BotChatSearch: найдено ${resp.results.size} чатов chatId=$chatId")

                if (resp.results.isEmpty()) {
                    sender.editText(
                        chatId, loadingMsgId,
                        "😕 Чаты не найдены\n\n" +
                            "По запросу «${query.take(60)}» подходящих чатов не найдено.\n\n" +
                            "Попробуйте уточнить или изменить запрос.",
                        keyboard(
                            row(btn("🔄 Новый поиск", "csearch:manual")),
                            row(btn("◀️ Назад к чатам", "menu:chats")),
                        ),
                    )
                    return@submit
                }

                // ── Отправляем результаты НОВЫМ сообщением (sendTextAndGetId) ───
                // Это гарантирует доставку: editText на старое "⏳..." сообщение
                // может провалиться если оно было удалено или слишком старое.
                // sendText никогда не падает по этим причинам.
                val resultsText   = buildResultsText(resp.results, query, 0, emptySet(), emptySet())
                val resultsMarkup = buildResultsMarkup(resp.results, 0, emptySet(), emptySet())

                val resultsMsgId = sender.sendTextAndGetId(chatId, resultsText, resultsMarkup)

                if (resultsMsgId == null) {
                    log.error("BotChatSearch: sendTextAndGetId вернул null — сообщение не отправлено chatId=$chatId")
                    sender.editText(
                        chatId, loadingMsgId,
                        "❌ Не удалось отправить результаты. Попробуйте ещё раз.",
                        keyboard(row(btn("🔄 Повторить", "csearch:manual"))),
                    )
                    return@submit
                }

                log.info("BotChatSearch: результаты отправлены chatId=$chatId resultsMsgId=$resultsMsgId")

                // Сохраняем сессию с ID сообщения результатов для последующих editText
                sessions[chatId] = UserSession(
                    step                = BotStep.WAITING_CHAT_SEARCH_QUERY,
                    msgId               = resultsMsgId,   // ← ID сообщения с результатами
                    chatSearchResults   = resp.results,
                    chatSearchQuery     = query,
                    chatSearchPage      = 0,
                    chatSearchAdded     = mutableSetOf(),
                    chatSearchDismissed = mutableSetOf(),
                )

                // Убираем loading-сообщение (заменяем тихим текстом)
                sender.editText(chatId, loadingMsgId, "✅ Результаты найдены, смотрите ниже.")

            } catch (e: Exception) {
                // Ловим всё — включая исключения из buildResultsText / sendTextAndGetId
                log.error(
                    "BotChatSearch: критическая ошибка chatId=$chatId " +
                    "query='${query.take(60)}': ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
                sender.editText(
                    chatId, loadingMsgId,
                    "❌ Ошибка поиска\n\n${e.message?.take(200) ?: "Попробуйте позже."}",
                    keyboard(
                        row(btn("🔄 Попробовать снова", "csearch:manual")),
                        row(btn("◀️ Назад", "menu:chats")),
                    ),
                )
            }
        }
    }

    // ─── Отображение результатов (вызывается из callback-ов навигации) ────────

    fun showResults(chatId: Long, msgId: Int, tgUserId: Long, page: Int) {
        val session = sessions[chatId]
        if (session == null || session.chatSearchResults.isEmpty()) {
            sender.editText(
                chatId, msgId,
                "Результаты поиска устарели. Выполните новый поиск.",
                keyboard(row(btn("🔍 Поиск", "csearch:start"))),
            )
            return
        }

        val text   = buildResultsText(
            session.chatSearchResults, session.chatSearchQuery, page,
            session.chatSearchAdded, session.chatSearchDismissed,
        )
        val markup = buildResultsMarkup(
            session.chatSearchResults, page,
            session.chatSearchAdded, session.chatSearchDismissed,
        )

        // Обновляем страницу в сессии
        val visible    = session.chatSearchResults.indices.filter { it !in session.chatSearchDismissed }
        val totalPages = ((visible.size + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE).coerceAtLeast(1)
        session.chatSearchPage = page.coerceIn(0, totalPages - 1)

        sender.editText(chatId, msgId, text, markup)
    }

    // ─── Построение текста результатов ───────────────────────────────────────

    /**
     * Строит текст страницы результатов.
     * Используем PLAIN TEXT — никакого parseMarkdown.
     * Markdown v1 Telegram ненадёжен для динамического контента
     * с произвольными символами в названиях каналов и описаниях.
     */
    private fun buildResultsText(
        results: List<io.getaimly.backend.lead.ChatSearchResult>,
        query: String,
        page: Int,
        added: Set<Int>,
        dismissed: Set<Int>,
    ): String {
        val visible    = results.indices.filter { it !in dismissed }
        val totalPages = ((visible.size + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE).coerceAtLeast(1)
        val safePage   = page.coerceIn(0, totalPages - 1)
        val pageItems  = visible.drop(safePage * SEARCH_PAGE_SIZE).take(SEARCH_PAGE_SIZE)
        val remaining  = visible.count { it !in added }

        val sb = StringBuilder()
        sb.append("🔍 Результаты поиска\n")

        val queryShort = query.take(50)
        if (queryShort.isNotBlank()) sb.append("Запрос: «$queryShort»\n")
        sb.append("\n")

        if (totalPages > 1) sb.append("Стр. ${safePage + 1}/$totalPages  •  ")
        sb.append("Найдено: ${visible.size}")
        if (remaining > 0) sb.append("  •  Осталось добавить: $remaining")
        sb.append("\n")
        sb.append("─".repeat(28))
        sb.append("\n\n")

        if (pageItems.isEmpty()) {
            sb.append("Все результаты просмотрены.")
            return sb.toString()
        }

        pageItems.forEachIndexed { localIdx, idx ->
            val r = results[idx]

            val status = when {
                idx in added       -> "✅ Добавлен"
                r.peerType == "chat" -> "💬 Группа"
                else               -> "📢 Канал"
            }

            val members = when {
                r.participantsCount >= 1_000_000 ->
                    "${r.participantsCount / 1_000_000}.${(r.participantsCount % 1_000_000) / 100_000}M уч."
                r.participantsCount >= 1_000 ->
                    "${r.participantsCount / 1_000}.${(r.participantsCount % 1_000) / 100}K уч."
                r.participantsCount > 0 ->
                    "${r.participantsCount} уч."
                else -> ""
            }

            val membersStr = if (members.isNotEmpty()) "  •  $members" else ""

            sb.append("${localIdx + 1}. ${r.title.take(50)}\n")
            sb.append("   $status$membersStr\n")

            if (!r.description.isNullOrBlank()) {
                val desc = r.description
                    .replace("\n", " ")
                    .replace("\r", "")
                    .trim()
                    .take(120)
                if (desc.isNotBlank()) sb.append("   $desc\n")
            }

            val linkDisplay = r.username ?: r.link.removePrefix("https://").take(50)
            sb.append("   $linkDisplay\n\n")
        }

        return sb.toString()
    }

    // ─── Построение клавиатуры результатов ───────────────────────────────────

    private fun buildResultsMarkup(
        results: List<io.getaimly.backend.lead.ChatSearchResult>,
        page: Int,
        added: Set<Int>,
        dismissed: Set<Int>,
    ): InlineKeyboardMarkup {
        val visible    = results.indices.filter { it !in dismissed }
        val totalPages = ((visible.size + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE).coerceAtLeast(1)
        val safePage   = page.coerceIn(0, totalPages - 1)
        val pageItems  = visible.drop(safePage * SEARCH_PAGE_SIZE).take(SEARCH_PAGE_SIZE)
        val remaining  = visible.count { it !in added }

        val rows = mutableListOf<InlineKeyboardRow>()

        // Кнопки каждого результата
        pageItems.forEach { idx ->
            val r         = results[idx]
            val isAdded   = idx in added
            val shortTitle = r.title.take(22).let { if (r.title.length > 22) "$it…" else it }

            if (isAdded) {
                rows.add(row(btn("✅ $shortTitle", "noop")))
            } else {
                rows.add(row(
                    btn("➕ $shortTitle",    "csearch:add:$idx"),
                    btn("✖ Пропустить", "csearch:skip:$idx"),
                ))
            }
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
        val notAdded = pageItems.filter { it !in added }
        if (notAdded.size > 1) {
            rows.add(row(btn("✅ Добавить все на странице", "csearch:add_page:$safePage")))
        }
        if (remaining > SEARCH_PAGE_SIZE) {
            rows.add(row(btn("✅ Добавить все ($remaining)", "csearch:add_all")))
        }

        rows.add(row(
            btn("🔄 Новый поиск", "csearch:manual"),
            btn("◀️ К чатам",     "menu:chats"),
        ))

        return InlineKeyboardMarkup(rows)
    }

    // ─── Добавить один чат ───────────────────────────────────────────────────

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
                log.info("BotChatSearch: userId=${user.id} добавил '${result.title}'")
                showResults(chatId, msgId, tgUserId, session.chatSearchPage)
            }
            .onFailure { e ->
                if (e.message?.contains("уже подписаны") == true || e.message?.contains("already") == true) {
                    session.chatSearchAdded.add(idx)
                    showResults(chatId, msgId, tgUserId, session.chatSearchPage)
                } else {
                    log.warn("BotChatSearch: addSubscription failed idx=$idx: ${e.message}")
                    sender.editText(
                        chatId, msgId,
                        "❌ Не удалось добавить «${result.title.take(40)}»:\n${e.message}\n\nНажмите «К результатам».",
                        keyboard(row(btn("◀️ К результатам", "csearch:page:${session.chatSearchPage}"))),
                    )
                }
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

        toAdd.forEach { idx ->
            runCatching { leadService.addSubscription(user, session.chatSearchResults[idx].link) }
                .onSuccess { session.chatSearchAdded.add(idx) }
                .onFailure { e ->
                    if (e.message?.contains("уже подписаны") == true) session.chatSearchAdded.add(idx)
                    else log.warn("BotChatSearch: addPage idx=$idx: ${e.message}")
                }
        }

        showResults(chatId, msgId, tgUserId, page)
    }

    // ─── Добавить все найденные ───────────────────────────────────────────────

    fun addAll(chatId: Long, msgId: Int, tgUserId: Long) {
        val user    = userRepository.findByTelegramId(tgUserId).orElse(null)
        val session = sessions[chatId]
        if (user == null || session == null) return

        val toAdd = session.chatSearchResults.indices
            .filter { it !in session.chatSearchDismissed && it !in session.chatSearchAdded }

        if (toAdd.isEmpty()) {
            showResults(chatId, msgId, tgUserId, session.chatSearchPage); return
        }

        sender.editText(chatId, msgId, "⏳ Добавляем ${toAdd.size} чатов...")

        worker.submit {
            try {
                var added = 0; var errors = 0
                toAdd.forEach { idx ->
                    runCatching { leadService.addSubscription(user, session.chatSearchResults[idx].link) }
                        .onSuccess { session.chatSearchAdded.add(idx); added++ }
                        .onFailure { e ->
                            if (e.message?.contains("уже подписаны") == true) {
                                session.chatSearchAdded.add(idx); added++
                            } else { errors++; log.warn("BotChatSearch: addAll idx=$idx: ${e.message}") }
                        }
                    Thread.sleep(300)
                }
                sessions.remove(chatId)
                val text = buildString {
                    append("✅ Готово!\n\nДобавлено чатов: $added\n")
                    if (errors > 0) append("Ошибок: $errors\n")
                    append("\nUserbot начнёт мониторинг в ближайшие минуты.")
                }
                sender.editText(chatId, msgId, text, keyboard(row(btn("◀️ К чатам", "menu:chats"))))
            } catch (e: Exception) {
                log.error("BotChatSearch: addAll critical err chatId=$chatId: ${e.message}", e)
                sender.sendText(chatId, "❌ Ошибка при добавлении: ${e.message?.take(100)}")
            }
        }
    }

    // ─── Скрыть результат ─────────────────────────────────────────────────────

    fun dismissResult(chatId: Long, msgId: Int, tgUserId: Long, idx: Int) {
        val session = sessions[chatId] ?: return
        session.chatSearchDismissed.add(idx)

        val visible    = session.chatSearchResults.indices.filter { it !in session.chatSearchDismissed }
        val totalPages = ((visible.size + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE).coerceAtLeast(1)
        val page       = session.chatSearchPage.coerceAtMost(totalPages - 1)

        showResults(chatId, msgId, tgUserId, page)
    }
}