package io.getaimly.backend.auth.dto

import jakarta.validation.constraints.*

data class RegisterRequest(
    @field:Email(message = "некорректный email")
    @field:NotBlank(message = "еmail обязателен")
    val email: String,

    @field:NotBlank(message = "пароль обязателен")
    @field:Size(min = 8, max = 100, message = "пароль должен быть от 8 до 100 символов")
    @field:Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
        message = "пароль должен содержать строчные, заглавные буквы и цифры",
    )
    val referralCode: String? = null,
    val password: String,

    @field:NotBlank(message = "подтверждение пароля обязательно")
    val confirmPassword: String,

    @field:Size(max = 100)
    val firstName: String? = null,
) {
    fun passwordsMatch() = password == confirmPassword
}