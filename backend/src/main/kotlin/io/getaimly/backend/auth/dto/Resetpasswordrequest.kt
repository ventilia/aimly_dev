package io.getaimly.backend.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class ForgotPasswordRequest(
    @field:NotBlank(message = "email обязателен")
    val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank(message = "код обязателен")
    @field:Pattern(regexp = "^\\d{6}$", message = "Код должен состоять из 6 цифр")
    val code: String,

    @field:NotBlank(message = "пароль обязателен")
    @field:Size(min = 8, max = 100, message = "пароль должен быть от 8 до 100 символов")
    @field:Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
        message = "пароль должен содержать строчные, заглавные буквы и цифры",
    )
    val newPassword: String,

    @field:NotBlank(message = "подтверждение пароля обязательно")
    val confirmPassword: String,
)