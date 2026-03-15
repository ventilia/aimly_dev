package io.getaimly.backend.auth.dto

data class AuthResponse(
    val token:                 String,
    val userId:                Long,
    val email:                 String,
    val firstName:             String?,
    val emailVerified:         Boolean,
    val telegramLinked:        Boolean,
    val telegramUsername:      String? = null,
    val role:                  String = "USER",
    val subscriptionStatus:    String? = null,
    val subscriptionPlan:      String? = null,
    val subscriptionExpiresAt: String? = null,
    val createdAt:             String? = null,
    val businessContext:       String? = null,
)

data class MessageResponse(val message: String)

data class TelegramLinkResponse(
    val linkToken:   String,
    val botUsername: String,
)