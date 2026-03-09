package io.getaimly.backend.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class VerifyEmailRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^\\d{6}$", message = "Код должен состоять из 6 цифр")
    val code: String,
)