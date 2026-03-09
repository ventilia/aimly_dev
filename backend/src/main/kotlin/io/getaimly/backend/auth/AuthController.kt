package io.getaimly.backend.auth

import io.getaimly.backend.auth.dto.*
import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.User
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val subscriptionExpiryRepository: SubscriptionExpiryRepository,
    @Value("\${telegram.bot.username}") private val botUsername: String,
    @Value("\${app.cookie.secure:true}") private val cookieSecure: Boolean,
    @Value("\${app.cookie.domain:}") private val cookieDomain: String,
) {

    private val COOKIE_NAME    = "aimly_auth"
    private val COOKIE_MAX_AGE = 60 * 60 * 24 * 30  // 30 дней

    @PostMapping("/telegram/unlink")
    fun unlinkTelegram(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<MessageResponse> =
        ResponseEntity.ok(authService.unlinkTelegram(user.id))


    @PostMapping("/register")
    fun register(
        @Valid @RequestBody request: RegisterRequest,
        response: HttpServletResponse,
    ): ResponseEntity<RegisterResponse> {
        val result = authService.register(request)

        if (result.token != null) {
            setAuthCookie(response, result.token)
        }
        return ResponseEntity.ok(result.copy(token = null))
    }


    @PostMapping("/verify-email")
    fun verifyEmail(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: VerifyEmailRequest,
        response: HttpServletResponse,
    ): ResponseEntity<AuthResponse> {
        val auth = authService.verifyEmail(user.id, request)
        setAuthCookie(response, auth.token)
        return ResponseEntity.ok(auth.copy(token = ""))
    }


    @PostMapping("/resend-code")
    fun resendCode(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<MessageResponse> =
        ResponseEntity.ok(authService.resendVerificationCode(user.id))


    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<LoginResponse> {
        val ip     = getClientIp(httpRequest)
        val result = authService.login(request, ip)

        return when (result) {
            is LoginResult.Success -> {
                setAuthCookie(response, result.auth.token)
                ResponseEntity.ok(
                    LoginResponse(
                        pendingVerification = false,
                        email               = result.auth.email,
                        tempToken           = null,
                        auth                = result.auth.copy(token = ""),
                    )
                )
            }

            is LoginResult.PendingVerification -> {
                setAuthCookie(response, result.token)
                ResponseEntity.ok(
                    LoginResponse(
                        pendingVerification = true,
                        email               = result.email,
                        tempToken           = null,
                        auth                = null,
                    )
                )
            }
        }
    }


    @PostMapping("/logout")
    fun logout(response: HttpServletResponse): ResponseEntity<MessageResponse> {
        clearAuthCookie(response)
        return ResponseEntity.ok(MessageResponse("выход выполнен"))
    }


    @PostMapping("/telegram/link")
    fun getTelegramLink(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<TelegramLinkResponse> =
        ResponseEntity.ok(authService.generateTelegramLinkToken(user.id, botUsername))


    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: User): ResponseEntity<AuthResponse> {
        val expiresAt = subscriptionExpiryRepository.findByUserId(user.id)?.expiresAt?.toString()
        return ResponseEntity.ok(
            AuthResponse(
                token                 = "",
                userId                = user.id,
                email                 = user.email,
                firstName             = user.firstName,
                emailVerified         = user.emailVerified,
                telegramLinked        = user.telegramId != null,
                telegramUsername      = user.telegramUsername,
                role                  = user.role.name,
                balance               = user.balance,
                subscriptionStatus    = user.subscriptionStatus,
                subscriptionPlan      = user.subscriptionPlan,
                subscriptionExpiresAt = expiresAt,
                createdAt             = user.createdAt?.toString(),
            )
        )
    }


    internal fun setAuthCookie(response: HttpServletResponse, token: String) {
        response.addHeader("Set-Cookie", buildCookieHeader(COOKIE_NAME, token, COOKIE_MAX_AGE))
    }


    internal fun clearAuthCookie(response: HttpServletResponse) {
        response.addHeader("Set-Cookie", buildCookieHeader(COOKIE_NAME, "", maxAge = 0))
    }

    private fun buildCookieHeader(name: String, value: String, maxAge: Int): String =
        buildString {
            append("$name=$value")
            append("; Path=/")
            append("; Max-Age=$maxAge")
            append("; HttpOnly")
            if (cookieSecure) append("; Secure")
            append("; SameSite=Lax")
            if (cookieDomain.isNotBlank()) append("; Domain=$cookieDomain")
        }

    private fun getClientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?: request.remoteAddr
}


data class LoginResponse(
    val pendingVerification: Boolean,
    val email:               String,
    val tempToken:           String?,
    val auth:                AuthResponse?,
)