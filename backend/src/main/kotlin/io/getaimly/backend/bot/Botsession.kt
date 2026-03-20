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
    WAITING_CHAT_SEARCH_QUERY,
}

data class UserSession(
    var step: BotStep,
    var email: String? = null,
    val msgId: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),

    var pendingAction: String? = null,

    var aiKeywordSuggestions: List<String> = emptyList(),
    var aiKeywordPage: Int = 0,
    var chatSearchResults: List<ChatSearchResult> = emptyList(),
    var chatSearchAdded: MutableSet<Int> = mutableSetOf(),
    var chatSearchDismissed: MutableSet<Int> = mutableSetOf(),
    var chatSearchPage: Int = 0,
    var chatSearchQuery: String = "",
    var chatSearchPeerType: String? = null,
)