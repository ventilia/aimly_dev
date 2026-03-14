package io.getaimly.backend.bot

import io.getaimly.backend.lead.ChatSearchResult
import java.time.LocalDateTime

enum class BotStep {
    WAITING_EMAIL,
    WAITING_PASSWORD,
    WAITING_CHAT_LINK,
    WAITING_KEYWORD,
    WAITING_CONTEXT,
    WAITING_AI_KEYWORD_CONFIRM,

    /** Ожидаем ввод запроса для AI-поиска чатов */
    WAITING_CHAT_SEARCH_QUERY,
}

data class UserSession(
    var step: BotStep,
    var email: String? = null,
    val msgId: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),

    // ─── AI-ключевые слова ────────────────────────────────────────────────────
    var aiKeywordSuggestions: List<String> = emptyList(),
    var aiKeywordPage: Int = 0,

    // ─── AI-поиск чатов ───────────────────────────────────────────────────────
    /** Результаты последнего поиска чатов */
    var chatSearchResults: List<ChatSearchResult> = emptyList(),
    /** Индексы (0-based) результатов, которые пользователь уже добавил */
    var chatSearchAdded: MutableSet<Int> = mutableSetOf(),
    /** Индексы (0-based) результатов, которые пользователь скрыл */
    var chatSearchDismissed: MutableSet<Int> = mutableSetOf(),
    /** Текущая страница просмотра результатов */
    var chatSearchPage: Int = 0,
    /** Запрос, по которому выполнен поиск (для отображения) */
    var chatSearchQuery: String = "",
)