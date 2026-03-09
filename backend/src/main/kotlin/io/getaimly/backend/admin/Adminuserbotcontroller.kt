package io.getaimly.backend.admin

import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.user.Role
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate

data class UserbotStatsDto(
    val status: String,
    val sessions: Int,
    val totalChats: Int,
    val totalUsers: Int,
    val perSession: List<Map<String, Any>>,
)

data class UserSubscriptionInfoDto(
    val userId: Long,
    val email: String,
    val chats: List<String>,
    val keywords: List<String>,
    val leadsCount: Long,
)

data class RegisterSessionRequest(
    val phone: String,
    val apiID: Int,
    val apiHash: String,
)

// Ответ Go-сервиса на /admin/sessions/register — возвращает tempId,
// который фронтенд должен сохранить и передать в confirm
data class RegisterSessionResponse(
    val tempId: String,
)

// Go-сервис ожидает именно поле "tempId" (не "sessionId")
// см. ConfirmSession handler: req.TempID string `json:"tempId"`
data class ConfirmSessionRequest(
    val tempId: String,
    val code: String,
    val password: String? = null,
)

@RestController
@RequestMapping("/api/v1/admin/userbot")
class AdminUserbotController(
    private val userRepository: UserRepository,
    private val subscriptionRepository: ChatSubscriptionRepository,
    private val keywordRepository: KeywordRepository,
    private val leadRepository: LeadRepository,
    @Value("\${userbot.url:http://localhost:9090}") private val userbotUrl: String,
    @Value("\${internal.api-secret:aimly_internal_secret_change_in_prod}") private val internalSecret: String,
) {
    private val log = LoggerFactory.getLogger(AdminUserbotController::class.java)

    private val restTemplate = RestTemplate()

    private val longRestTemplate: RestTemplate = RestTemplate(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(5_000)
            setReadTimeout(3 * 60 * 1000) // 3 минуты
        }
    )

    private fun requireAdmin(user: User) {
        if (user.role != Role.ADMIN) throw ForbiddenException("доступ только для администраторов")
    }

    private fun internalHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Internal-Secret", internalSecret)
    }

    @GetMapping("/stats")
    fun getStats(@AuthenticationPrincipal user: User): ResponseEntity<Any> {
        requireAdmin(user)
        return try {
            // /stats защищён X-Internal-Secret — нужен заголовок, иначе Go вернёт 403
            val entity = HttpEntity<Void>(internalHeaders())
            val response = restTemplate.exchange(
                "$userbotUrl/stats",
                org.springframework.http.HttpMethod.GET,
                entity,
                Map::class.java,
            )
            val stats = response.body ?: throw RuntimeException("пустой ответ")

            // Go не возвращает поле status — добавляем сами, раз сервис ответил
            val result = stats.toMutableMap()
            result["status"] = "UP"

            ResponseEntity.ok(result)
        } catch (e: Exception) {
            log.warn("Go-сервис недоступен: ${e.message}")
            ResponseEntity.ok(
                mapOf(
                    "status"     to "DOWN",
                    "sessions"   to 0,
                    "totalChats" to 0,
                    "totalUsers" to 0,
                    "perSession" to emptyList<Any>(),
                )
            )
        }
    }

    @GetMapping("/users")
    fun getUsersInfo(@AuthenticationPrincipal user: User): ResponseEntity<List<UserSubscriptionInfoDto>> {
        requireAdmin(user)

        val users = userRepository.findAll()
        val result = users.mapNotNull { u ->
            val chats = subscriptionRepository.findByUserIdAndIsActiveTrue(u.id)
            val keywords = keywordRepository.findByUserIdAndIsActiveTrue(u.id)
            if (chats.isEmpty() && keywords.isEmpty()) return@mapNotNull null

            UserSubscriptionInfoDto(
                userId = u.id,
                email = u.email,
                chats = chats.map { it.chatTitle.ifBlank { it.chatLink } },
                keywords = keywords.map { it.keyword },
                leadsCount = leadRepository.countByUserId(u.id),
            )
        }

        return ResponseEntity.ok(result)
    }

    // Шаг 1: отправляем номер телефона, получаем tempId
    // Go возвращает: { "tempId": "uuid-строка" }
    // Фронтенд должен сохранить tempId и передать его в /sessions/confirm
    @PostMapping("/sessions/register")
    fun registerSession(
        @AuthenticationPrincipal user: User,
        @RequestBody req: RegisterSessionRequest,
    ): ResponseEntity<Any> {
        requireAdmin(user)
        return try {
            val goReq = mapOf(
                "phone"   to req.phone,
                "apiId"   to req.apiID,   // Go ждёт "apiId" (camelCase), не "apiID"
                "apiHash" to req.apiHash,
            )
            val entity = HttpEntity(goReq, internalHeaders())
            val resp = longRestTemplate.postForObject(
                "$userbotUrl/admin/sessions/register",
                entity,
                Map::class.java,
            )
            log.info("userbot register OK: phone=${req.phone} tempId=${resp?.get("tempId")}")
            ResponseEntity.ok(resp)
        } catch (e: Exception) {
            log.error("ошибка регистрации сессии userbot: ${e.message}")
            ResponseEntity.internalServerError().body(mapOf("error" to (e.message ?: "ошибка")))
        }
    }

    // Шаг 2: передаём tempId (из ответа register) + код из Telegram + пароль 2FA (если есть)
    // Go ждёт: { "tempId": "...", "code": "...", "password": "..." }
    @PostMapping("/sessions/confirm")
    fun confirmSession(
        @AuthenticationPrincipal user: User,
        @RequestBody req: ConfirmSessionRequest,
    ): ResponseEntity<Any> {
        requireAdmin(user)
        return try {
            val goReq = mapOf(
                "tempId"   to req.tempId,
                "code"     to req.code,
                "password" to (req.password ?: ""),
            )
            val entity = HttpEntity(goReq, internalHeaders())
            val resp = longRestTemplate.postForObject(
                "$userbotUrl/admin/sessions/confirm",
                entity,
                Map::class.java,
            )
            log.info("userbot confirm OK: sessionId=${resp?.get("sessionId")} phone=${resp?.get("phone")}")
            ResponseEntity.ok(resp)
        } catch (e: Exception) {
            log.error("ошибка подтверждения сессии userbot: ${e.message}")
            ResponseEntity.internalServerError().body(mapOf("error" to (e.message ?: "ошибка")))
        }
    }
}