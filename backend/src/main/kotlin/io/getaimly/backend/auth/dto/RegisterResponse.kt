package io.getaimly.backend.auth.dto



data class RegisterResponse(
    val message: String,
    val userId:  Long?,
    val token:   String?,
)