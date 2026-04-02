package io.getaimly.backend.bot

import io.getaimly.backend.lead.ChatSearchResult
import java.time.LocalDateTime

enum class BotStep {
    // ── Авторизация (вход) ─────────────────────────────────────────────────
    WAITING_EMAIL,
    WAITING_PASSWORD,
    /** Ввод 6-значного кода подтверждения email при входе через бота */
    WAITING_LOGIN_CODE,

    // ── Регистрация через бот ──────────────────────────────────────────────
    WAITING_REG_EMAIL,
    WAITING_REG_PASSWORD,
    WAITING_REG_PASSWORD_CONFIRM,

    // ── Сброс пароля через бот ────────────────────────────────────────────
    WAITING_RESET_EMAIL,
    WAITING_RESET_CODE,
    WAITING_RESET_NEW_PASSWORD,
    WAITING_RESET_NEW_PASSWORD_CONFIRM,

    // ── Управление чатами ─────────────────────────────────────────────────
    WAITING_CHAT_LINK,

    // ── Ключевые слова ────────────────────────────────────────────────────
    WAITING_KEYWORD,
    WAITING_CONTEXT,
    WAITING_AI_KEYWORD_CONFIRM,

    // ── Поиск чатов ───────────────────────────────────────────────────────
    WAITING_CHAT_SEARCH_QUERY,

    WAITING_EXPORT_FILE,
}

data class UserSession(
    var step: BotStep,
    var email: String? = null,
    val msgId: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),

    var pendingAction: String? = null,

    var pendingReferralCode: String? = null,

    var regEmail:    String? = null,
    var regPassword: String? = null,

    var pendingVerificationUserId: Long? = null,

    // ── Сброс пароля ──────────────────────────────────────────────────────
    var resetEmail:       String? = null,
    var resetNewPassword: String? = null,

    var aiKeywordSuggestions: List<String> = emptyList(),
    var aiKeywordPage: Int = 0,
    var chatSearchResults: List<ChatSearchResult> = emptyList(),
    var chatSearchAdded: MutableSet<Int> = mutableSetOf(),
    var chatSearchDismissed: MutableSet<Int> = mutableSetOf(),
    var chatSearchPage: Int = 0,
    var chatSearchQuery: String = "",
    var chatSearchPeerType: String? = null,

    var exportMsgId: Int = 0,
)