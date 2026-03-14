package io.getaimly.backend.bot

import io.getaimly.backend.ai.AiService
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.lead.LeadService
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val KW_PAGE_SIZE    = 5
private const val AI_SUGGEST_SHOW = 5
private const val MAX_KEYWORDS    = 50


class BotKeywordsHandler(
    private val sender: BotSender,
    private val sessions: ConcurrentHashMap<Long, UserSession>,
    private val userRepository: UserRepository,
    private val keywordRepository: KeywordRepository,
    private val leadService: LeadService,
    private val aiService: AiService,
) {

    private val log      = LoggerFactory.getLogger(BotKeywordsHandler::class.java)
    private val aiWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bot-ai-kw-worker").also { it.isDaemon = true }
    }


    fun showKeywords(chatId: Long, msgId: Int, tgUserId: Long, page: Int = 0) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val keywords = keywordRepository.findByUserIdAndIsActiveTrue(user.id)
        val plan     = user.subscriptionPlan
        val hasAi    = plan == "MINIMUM" || plan == "START" || user.subscriptionStatus == "TRIAL"

        // Метка тумблера — одинакова для обоих экранов (пустой и с данными)
        val toggleLabel = if (user.respondToServiceOffers)
            "🟢 Предложения услуг: ВКЛ"
        else
            "⚫️ Предложения услуг: ВЫКЛ"

        if (keywords.isEmpty()) {
            val rows = mutableListOf<InlineKeyboardRow>()
            rows.add(row(btn("➕ Добавить слово", "kw:add")))
            if (hasAi && !user.businessContext.isNullOrBlank()) {
                rows.add(row(btn("✨ Сгенерировать AI-слова", "kw:ai:generate")))
            }
            rows.add(row(btn(toggleLabel, "kw:toggle_service_offers")))
            rows.add(row(btn("◀️ Главное меню", "menu:back")))
            sender.editText(
                chatId, msgId,
                "🔍 *Ключевые слова*\n\nУ вас ещё нет ключевых слов.\n\n" +
                        "Добавьте слово или фразу — при совпадении в чатах придёт уведомление.\n\n" +
                        "💡 Примеры: «ищу дизайнера», «нужен разработчик»",
                InlineKeyboardMarkup(rows),
                parseMarkdown = true,
            )
            return
        }

        val totalPages = (keywords.size + KW_PAGE_SIZE - 1) / KW_PAGE_SIZE
        val safePage   = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val from       = safePage * KW_PAGE_SIZE
        val to         = (from + KW_PAGE_SIZE).coerceAtMost(keywords.size)
        val pageKws    = keywords.subList(from, to)

        val sb = StringBuilder("🔍 *Ключевые слова* (${keywords.size}/$MAX_KEYWORDS)")
        if (totalPages > 1) sb.append("  •  стр. ${safePage + 1}/$totalPages")
        sb.append("\n\n")
        pageKws.forEach { kw ->
            val varCount = kw.variants?.split(",")?.filter { it.isNotBlank() }?.size ?: 0
            sb.append("• «${kw.keyword.md()}»")
            if (varCount > 0) sb.append(" _+${varCount} вар._")
            sb.append("\n")
        }
        sb.append("\n_Нажмите на слово чтобы удалить_")

        val rows = mutableListOf<InlineKeyboardRow>()
        pageKws.forEach { kw ->
            rows.add(row(btn("🗑 «${kw.keyword.take(30)}»", "kw:del:${kw.id}")))
        }

        if (totalPages > 1) {
            val navBtns = mutableListOf<InlineKeyboardButton>()
            if (safePage > 0)              navBtns.add(btn("◀️", "kw:page:${safePage - 1}"))
            navBtns.add(btn("${safePage + 1}/$totalPages", "noop"))
            if (safePage < totalPages - 1) navBtns.add(btn("▶️", "kw:page:${safePage + 1}"))
            rows.add(InlineKeyboardRow(navBtns))
        }

        val actionBtns = mutableListOf<InlineKeyboardButton>()
        actionBtns.add(btn("➕ Добавить", "kw:add"))
        if (hasAi && !user.businessContext.isNullOrBlank()) {
            actionBtns.add(btn("✨ AI", "kw:ai:generate"))
        }
        rows.add(InlineKeyboardRow(actionBtns))

        rows.add(row(btn(toggleLabel, "kw:toggle_service_offers")))
        rows.add(row(btn("◀️ Главное меню", "menu:back")))

        sender.editText(chatId, msgId, sb.toString(), InlineKeyboardMarkup(rows), parseMarkdown = true)
    }


    /**
     * Переключает флаг respondToServiceOffers и обновляет экран ключевых слов.
     */
    fun toggleServiceOffers(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
            ?: run { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        // Перечитываем из БД для получения актуального значения флага
        val freshUser = userRepository.findById(user.id).orElse(null) ?: return
        freshUser.respondToServiceOffers = !freshUser.respondToServiceOffers
        userRepository.save(freshUser)

        log.info(
            "toggleServiceOffers: userId=${freshUser.id} " +
                    "respondToServiceOffers=${freshUser.respondToServiceOffers}"
        )

        // Обновляем страницу: тумблер отражает новое состояние
        showKeywords(chatId, msgId, tgUserId)
    }


    fun startAddKeyword(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user != null) {
            val count = keywordRepository.findByUserIdAndIsActiveTrue(user.id).size
            if (count >= MAX_KEYWORDS) {
                sender.editText(
                    chatId, msgId,
                    "⚠️ *Достигнут лимит ключевых слов*\n\n" +
                            "Максимально допустимое количество — $MAX_KEYWORDS слов.\n\n" +
                            "Удалите неактуальные слова, чтобы добавить новые.",
                    keyboard(row(btn("◀️ К ключевым словам", "menu:keywords"))),
                    parseMarkdown = true,
                )
                return
            }
        }
        sessions[chatId] = UserSession(step = BotStep.WAITING_KEYWORD, msgId = msgId)
        sender.editText(
            chatId, msgId,
            "🔍 *Добавить ключевое слово*\n\n" +
                    "Отправьте слово или фразу для поиска.\n\n" +
                    "💡 Примеры:\n" +
                    "• `ищу дизайнера`\n" +
                    "• `нужен разработчик`\n" +
                    "• `ищем копирайтера`\n\n" +
                    "🤖 AI автоматически создаст морфологические варианты.",
            keyboard(row(btn("❌ Отмена", "menu:keywords"))),
            parseMarkdown = true,
        )
    }

    fun handleKeywordInput(chatId: Long, text: String, from: org.telegram.telegrambots.meta.api.objects.User) {
        val session    = sessions.remove(chatId) ?: return
        val savedMsgId = session.msgId
        val user       = userRepository.findByTelegramId(from.id).orElse(null)
            ?: run { sender.sendText(chatId, "Нужно войти. /start"); return }

        runCatching { leadService.addKeyword(user, text) }
            .onSuccess { kw ->
                val variants     = kw.variants.take(3).joinToString(", ") { it.md() }
                val variantsLine = if (kw.variants.isNotEmpty()) "\n\n🤖 AI добавит варианты поиска:\n_${variants}…_" else ""
                if (savedMsgId != 0) {
                    sender.editText(
                        chatId, savedMsgId,
                        "✅ *Ключевое слово добавлено!*\n\n🔍 «${kw.keyword.md()}»$variantsLine",
                        keyboard(
                            row(btn("➕ Добавить ещё",      "kw:add")),
                            row(btn("◀️ К ключевым словам", "menu:keywords")),
                        ),
                        parseMarkdown = true,
                    )
                } else {
                    sender.sendText(chatId, "✅ Добавлено: «${kw.keyword}»")
                }
            }
            .onFailure { e ->
                if (savedMsgId != 0) {
                    sender.editText(
                        chatId, savedMsgId,
                        "❌ Ошибка: ${e.message}",
                        keyboard(
                            row(btn("🔄 Попробовать снова", "kw:add")),
                            row(btn("◀️ Назад",              "menu:back")),
                        ),
                    )
                } else {
                    sender.sendText(chatId, "❌ Ошибка: ${e.message}")
                }
            }
    }


    fun showDeleteKeywordConfirm(chatId: Long, msgId: Int, tgUserId: Long, kwId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val kw    = keywordRepository.findByUserIdAndIsActiveTrue(user.id).find { it.id == kwId }
        val label = kw?.keyword ?: "это ключевое слово"

        sender.editText(
            chatId, msgId,
            "🗑 *Удалить ключевое слово?*\n\n🔍 «${label.md()}»\n\n" +
                    "Лиды по этому слову больше приходить не будут.",
            keyboard(
                row(
                    btn("✅ Да, удалить", "kw:del:confirm:$kwId"),
                    btn("❌ Отмена",       "kw:del:cancel"),
                ),
            ),
            parseMarkdown = true,
        )
    }

    fun deleteKeyword(tgUserId: Long, kwId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return
        runCatching { leadService.removeKeyword(user, kwId) }
            .onFailure { log.warn("deleteKeyword failed kwId=$kwId: ${it.message}") }
    }


    fun startAiGeneration(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) { sender.editText(chatId, msgId, "Нужно войти. /start"); return }

        val plan  = user.subscriptionPlan
        val hasAi = plan == "MINIMUM" || plan == "START" || user.subscriptionStatus == "TRIAL"

        if (!hasAi) {
            sender.editText(
                chatId, msgId,
                "🔒 *AI-генерация недоступна*\n\n" +
                        "Функция доступна на тарифах *МИНИМУМ* и выше.\n\n" +
                        "AI анализирует ваш бизнес и подбирает ключевые слова для мониторинга.",
                keyboard(
                    row(urlBtn("💳 Выбрать тариф", BotAuthHandler.SITE_URL + "/checkout")),
                    row(btn("◀️ Назад", "menu:keywords")),
                ),
                parseMarkdown = true,
            )
            return
        }

        val context = user.businessContext
        if (context.isNullOrBlank() || context.trim().length < 20) {
            sender.editText(
                chatId, msgId,
                "⚠️ *Бизнес-контекст не задан*\n\n" +
                        "Для генерации ключевых слов сначала опишите ваш бизнес в разделе «Профиль → AI-персонализация».\n\n" +
                        "Описание должно содержать минимум 20 символов.",
                keyboard(
                    row(btn("👤 Перейти в профиль", "menu:profile")),
                    row(btn("◀️ Назад",              "menu:keywords")),
                ),
                parseMarkdown = true,
            )
            return
        }

        sender.editText(
            chatId, msgId,
            "✨ *AI генерирует ключевые слова…*\n\n" +
                    "Анализирую описание вашего бизнеса и подбираю фразы, которые пишут потенциальные клиенты.\n\n" +
                    "_Обычно это занимает 5–15 секунд._",
            parseMarkdown = true,
        )

        aiWorker.submit {
            runCatching {
                aiService.generateKeywords(context)
            }.onSuccess { keywords ->
                if (keywords.isEmpty()) {
                    sender.editText(
                        chatId, msgId,
                        "😕 AI не смог подобрать ключевые слова.\n\nПопробуйте уточнить описание бизнеса.",
                        keyboard(
                            row(btn("👤 Изменить описание", "menu:profile")),
                            row(btn("◀️ Назад",              "menu:keywords")),
                        ),
                    )
                    return@submit
                }

                sessions[chatId] = UserSession(
                    step                 = BotStep.WAITING_AI_KEYWORD_CONFIRM,
                    msgId                = msgId,
                    aiKeywordSuggestions = keywords,
                    aiKeywordPage        = 0,
                )
                showAiSuggestions(chatId, msgId, tgUserId)

            }.onFailure { e ->
                log.warn("AI keyword generation failed for userId=${user.id}: ${e.message}")
                sender.editText(
                    chatId, msgId,
                    "❌ *Ошибка генерации*\n\n${e.message ?: "Попробуйте позже."}",
                    keyboard(
                        row(btn("🔄 Повторить", "kw:ai:generate")),
                        row(btn("◀️ Назад",      "menu:keywords")),
                    ),
                    parseMarkdown = true,
                )
            }
        }
    }


    private fun showAiSuggestions(chatId: Long, msgId: Int, tgUserId: Long) {
        val session     = sessions[chatId] ?: return
        val suggestions = session.aiKeywordSuggestions
        val user        = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return

        val safePage   = session.aiKeywordPage
        val totalPages = (suggestions.size + AI_SUGGEST_SHOW - 1) / AI_SUGGEST_SHOW
        val from       = safePage * AI_SUGGEST_SHOW
        val to         = (from + AI_SUGGEST_SHOW).coerceAtMost(suggestions.size)
        val pageSuggestions = suggestions.subList(from, to)

        val currentCount = keywordRepository.findByUserIdAndIsActiveTrue(user.id).size
        val available    = (MAX_KEYWORDS - currentCount).coerceAtLeast(0)

        val sb = StringBuilder("✨ *AI-подборка ключевых слов*")
        if (totalPages > 1) sb.append("  •  стр. ${safePage + 1}/$totalPages")
        sb.append("\n\n")
        if (available == 0) {
            sb.append("⚠️ Достигнут лимит $MAX_KEYWORDS слов.\n\n")
        } else {
            sb.append("Доступно мест: $available из $MAX_KEYWORDS\n\n")
        }
        pageSuggestions.forEachIndexed { idx, kw ->
            sb.append("${idx + 1}. «${kw.md()}»\n")
        }
        sb.append("\n_Нажмите на слово чтобы принять, или используйте кнопки ниже_")

        val rows = mutableListOf<InlineKeyboardRow>()

        pageSuggestions.forEachIndexed { idx, kw ->
            val globalIdx = from + idx
            rows.add(row(
                btn("✅ «${kw.take(25)}»", "kw:ai:accept:$globalIdx"),
                btn("❌",                   "kw:ai:reject:$globalIdx"),
            ))
        }

        if (available > 0) {
            rows.add(row(
                btn("✅ Принять страницу", "kw:ai:accept_page:$safePage"),
                btn("✅ Принять все",      "kw:ai:accept_all"),
            ))
        }
        rows.add(row(btn("🗑 Отклонить все", "kw:ai:reject_all")))

        if (totalPages > 1) {
            val navBtns = mutableListOf<InlineKeyboardButton>()
            if (safePage > 0)              navBtns.add(btn("◀️", "kw:ai:page:${safePage - 1}"))
            navBtns.add(btn("${safePage + 1}/$totalPages", "noop"))
            if (safePage < totalPages - 1) navBtns.add(btn("▶️", "kw:ai:page:${safePage + 1}"))
            rows.add(InlineKeyboardRow(navBtns))
        }

        rows.add(row(btn("◀️ Отмена", "kw:ai:reject_all")))

        sender.editText(chatId, msgId, sb.toString(), InlineKeyboardMarkup(rows), parseMarkdown = true)
    }


    fun acceptAiSuggestion(chatId: Long, msgId: Int, tgUserId: Long, globalIdx: Int) {
        val session = sessions[chatId] ?: return
        val suggestions = session.aiKeywordSuggestions
        if (globalIdx < 0 || globalIdx >= suggestions.size) return

        val kw   = suggestions[globalIdx]
        val user = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return

        runCatching { leadService.addKeyword(user, kw) }
            .onFailure { log.warn("acceptAiSuggestion failed kw='$kw': ${it.message}") }

        val updated = suggestions.toMutableList().also { it.removeAt(globalIdx) }
        session.aiKeywordSuggestions = updated

        if (updated.isEmpty()) {
            sessions.remove(chatId)
            sender.editText(
                chatId, msgId,
                "✅ Все ключевые слова добавлены!",
                keyboard(row(btn("◀️ К ключевым словам", "menu:keywords"))),
            )
            return
        }

        val totalPages = (updated.size + AI_SUGGEST_SHOW - 1) / AI_SUGGEST_SHOW
        if (session.aiKeywordPage >= totalPages) session.aiKeywordPage = totalPages - 1

        showAiSuggestions(chatId, msgId, tgUserId)
    }


    fun rejectAiSuggestion(chatId: Long, msgId: Int, tgUserId: Long, globalIdx: Int) {
        val session = sessions[chatId] ?: return
        val suggestions = session.aiKeywordSuggestions
        if (globalIdx < 0 || globalIdx >= suggestions.size) return

        val updated = suggestions.toMutableList().also { it.removeAt(globalIdx) }
        session.aiKeywordSuggestions = updated

        if (updated.isEmpty()) {
            sessions.remove(chatId)
            sender.editText(
                chatId, msgId,
                "Все предложения отклонены.",
                keyboard(row(btn("◀️ К ключевым словам", "menu:keywords"))),
            )
            return
        }

        val totalPages = (updated.size + AI_SUGGEST_SHOW - 1) / AI_SUGGEST_SHOW
        if (session.aiKeywordPage >= totalPages) session.aiKeywordPage = totalPages - 1

        showAiSuggestions(chatId, msgId, tgUserId)
    }


    fun acceptAiPage(chatId: Long, msgId: Int, tgUserId: Long, page: Int) {
        val session     = sessions[chatId] ?: return
        val suggestions = session.aiKeywordSuggestions
        val user        = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return

        val from    = page * AI_SUGGEST_SHOW
        val to      = (from + AI_SUGGEST_SHOW).coerceAtMost(suggestions.size)
        val pageKws = suggestions.subList(from, to).toList()

        var addedCount = 0
        pageKws.forEach { kw ->
            runCatching { leadService.addKeyword(user, kw) }
                .onSuccess { addedCount++ }
                .onFailure { log.warn("acceptAiPage: failed to add '$kw': ${it.message}") }
        }

        val updated = suggestions.toMutableList().also { it.removeAll(pageKws.toSet()) }
        session.aiKeywordSuggestions = updated

        if (updated.isEmpty()) {
            sessions.remove(chatId)
            sender.editText(
                chatId, msgId,
                "✅ Добавлено $addedCount ключевых слов!",
                keyboard(row(btn("◀️ К ключевым словам", "menu:keywords"))),
            )
            return
        }

        val totalPages = (updated.size + AI_SUGGEST_SHOW - 1) / AI_SUGGEST_SHOW
        if (session.aiKeywordPage >= totalPages) session.aiKeywordPage = totalPages - 1

        showAiSuggestions(chatId, msgId, tgUserId)
    }


    fun acceptAllAiSuggestions(chatId: Long, msgId: Int, tgUserId: Long) {
        val session     = sessions.remove(chatId) ?: return
        val suggestions = session.aiKeywordSuggestions
        val user        = userRepository.findByTelegramId(tgUserId).orElse(null) ?: return

        val currentCount = keywordRepository.findByUserIdAndIsActiveTrue(user.id).size
        val available    = (MAX_KEYWORDS - currentCount).coerceAtLeast(0)
        val toAdd        = suggestions.take(available)

        var addedCount   = 0
        var skippedCount = 0

        toAdd.forEach { kw ->
            runCatching { leadService.addKeyword(user, kw) }
                .onSuccess { addedCount++ }
                .onFailure { skippedCount++ }
        }

        val skippedDueToLimit = suggestions.size - toAdd.size
        val summary = buildString {
            append("✅ *Готово!*\n\n")
            append("Добавлено: $addedCount слов\n")
            if (skippedCount > 0)      append("Пропущено (дубликаты): $skippedCount\n")
            if (skippedDueToLimit > 0) append("Не вошло (лимит $MAX_KEYWORDS): $skippedDueToLimit\n")
        }

        sender.editText(
            chatId, msgId,
            summary,
            keyboard(row(btn("◀️ К ключевым словам", "menu:keywords"))),
            parseMarkdown = true,
        )
    }


    fun rejectAllAiSuggestions(chatId: Long, msgId: Int) {
        sessions.remove(chatId)
        sender.editText(
            chatId, msgId,
            "Все AI-предложения отклонены.",
            keyboard(row(btn("◀️ К ключевым словам", "menu:keywords"))),
        )
    }

    fun setAiPage(chatId: Long, msgId: Int, tgUserId: Long, page: Int) {
        val session = sessions[chatId] ?: return
        session.aiKeywordPage = page
        showAiSuggestions(chatId, msgId, tgUserId)
    }
}