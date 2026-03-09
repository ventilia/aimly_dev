package io.getaimly.backend.auth

import com.fasterxml.jackson.annotation.JsonProperty
import io.getaimly.backend.auth.dto.AuthResponse
import io.getaimly.backend.security.JwtService
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import jakarta.servlet.http.HttpServletResponse
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestClient
import java.time.LocalDateTime


data class GoogleAuthRequest(
    @JsonProperty("id_token") val idToken: String,
)

data class GoogleTokenInfo(
    @JsonProperty("sub")            val sub:           String,
    @JsonProperty("email")          val email:         String,
    @JsonProperty("email_verified") val emailVerified: String,
    @JsonProperty("given_name")     val givenName:     String?,
    @JsonProperty("name")           val name:          String?,
    @JsonProperty("aud")            val aud:           String,
)


@RestController
@RequestMapping("/api/v1/auth/oauth2")
class GoogleOAuthController(
    private val userRepository: UserRepository,
    private val jwtService:     JwtService,
    private val authController: AuthController,
    @Value("\${google.client-id}") private val googleClientId: String,
) {
    private val log    = LoggerFactory.getLogger(GoogleOAuthController::class.java)
    private val client = RestClient.create()


    @PostMapping("/google")
    @Transactional
    fun googleLogin(
        @RequestBody request: GoogleAuthRequest,
        response: HttpServletResponse,
    ): ResponseEntity<AuthResponse> {

        val tokenInfo = verifyGoogleToken(request.idToken)

        if (tokenInfo.aud != googleClientId) {
            log.warn("google id_token выдан для другого приложения: ${tokenInfo.aud}")
            throw BadRequestException("Недействительный токен Google")
        }

        if (tokenInfo.emailVerified != "true") {
            throw BadRequestException("Email не подтверждён в Google")
        }

        val email = tokenInfo.email.lowercase()

        // ищем пользователя по googleId или email, если не нашли — создаём
        val user = userRepository.findByGoogleId(tokenInfo.sub).orElse(null)
            ?: userRepository.findByEmail(email).orElse(null)
            ?: createGoogleUser(email, tokenInfo)


        if (user.googleId == null) {
            user.googleId      = tokenInfo.sub
            user.emailVerified = true
            user.updatedAt     = LocalDateTime.now()
            if (user.firstName == null) {
                user.firstName = tokenInfo.givenName
            }
        }

        if (!user.isActive) {
            throw ForbiddenException("Аккаунт заблокирован")
        }

        val auth = buildGoogleAuthResponse(user)

        // используем тот же метод что в AuthController — единые настройки куки
        authController.setAuthCookie(response, auth.token)

        log.info("вход через Google: userId=${user.id} email=$email")
        return ResponseEntity.ok(auth.copy(token = ""))  // токен в куке, не в теле
    }


    private fun verifyGoogleToken(idToken: String): GoogleTokenInfo =
        try {
            client.get()
                .uri("https://oauth2.googleapis.com/tokeninfo?id_token=$idToken")
                .retrieve()
                .body(GoogleTokenInfo::class.java)
                ?: throw BadRequestException("Не удалось верифицировать токен Google")
        } catch (e: BadRequestException) {
            throw e
        } catch (e: Exception) {
            log.warn("ошибка верификации Google token: ${e.message}")
            throw BadRequestException("Недействительный токен Google")
        }

    private fun createGoogleUser(email: String, info: GoogleTokenInfo): User {
        val user = User(
            email         = email,
            password      = null,
            firstName     = info.givenName,
            googleId      = info.sub,
            emailVerified = true,
        )
        log.info("создан новый пользователь через Google: email=$email")
        return userRepository.save(user)
    }

    private fun buildGoogleAuthResponse(user: User) = AuthResponse(
        token              = jwtService.generateToken(user.id, user.email),
        userId             = user.id,
        email              = user.email,
        firstName          = user.firstName,
        emailVerified      = user.emailVerified,
        telegramLinked     = user.telegramId != null,
        role               = user.role.name,
        balance            = user.balance,
        subscriptionStatus = user.subscriptionStatus,
        subscriptionPlan   = user.subscriptionPlan,
    )
}