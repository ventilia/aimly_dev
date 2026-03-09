package io.getaimly.backend.bot

import java.time.LocalDateTime


enum class BotStep {
    WAITING_EMAIL,
    WAITING_PASSWORD,
    WAITING_CHAT_LINK,
    WAITING_KEYWORD,
    WAITING_CONTEXT,
}

data class UserSession(
    var step: BotStep,
    var email: String? = null,
    val msgId: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)