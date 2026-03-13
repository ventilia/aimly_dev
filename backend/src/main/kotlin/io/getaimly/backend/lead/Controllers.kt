package io.getaimly.backend.lead

import io.getaimly.backend.ai.AiService
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType


data class UpdateStatusRequest(val status: String)

@RestController
@RequestMapping("/api/v1/leads")
class LeadController(private val service: LeadService) {

    @GetMapping
    fun getLeads(
        @AuthenticationPrincipal user: User,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<LeadPageDto> =
        ResponseEntity.ok(service.getLeads(user, status, page, size))

    @PatchMapping("/{id}/status")
    fun updateStatus(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
        @RequestBody req: UpdateStatusRequest,
    ): ResponseEntity<LeadDto> =
        ResponseEntity.ok(service.updateLeadStatus(user, id, req.status))
}


@RestController
@RequestMapping("/internal")
class InternalController(
    private val service: LeadService,
    @Value("\${internal.api-secret:aimly_internal_secret_change_in_prod}") private val secret: String,
) {
    @PostMapping("/messages/incoming")
    fun incoming(
        @RequestHeader("X-Internal-Secret") clientSecret: String,
        @RequestBody req: IncomingMessageRequest,
    ): ResponseEntity<Map<String, String>> {
        if (clientSecret != secret) return ResponseEntity.status(401).body(mapOf("error" to "unauthorized"))
        service.processIncomingMessage(req)
        return ResponseEntity.ok(mapOf("status" to "ok"))
    }
}


data class AddChatRequest(val chatLink: String)

@RestController
@RequestMapping("/api/v1/chats")
class ChatSubscriptionController(private val service: LeadService) {

    @GetMapping
    fun list(@AuthenticationPrincipal user: User): ResponseEntity<List<ChatSubscriptionDto>> =
        ResponseEntity.ok(service.getSubscriptions(user))

    @PostMapping
    fun add(
        @AuthenticationPrincipal user: User,
        @RequestBody req: AddChatRequest,
    ): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(service.addSubscription(user, req.chatLink))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to (e.message ?: "Чат уже добавлен")))
        }
    }

    @DeleteMapping("/{id}")
    fun remove(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.removeSubscription(user, id)
        return ResponseEntity.noContent().build()
    }
}


data class AddKeywordRequest(val keyword: String)
data class GenerateKeywordsRequest(val businessContext: String)
data class GenerateKeywordsResponse(val keywords: List<String>)

@RestController
@RequestMapping("/api/v1/keywords")
class KeywordController(
    private val service: LeadService,
    private val aiService: AiService,
) {

    @GetMapping
    fun list(@AuthenticationPrincipal user: User): ResponseEntity<List<KeywordDto>> =
        ResponseEntity.ok(service.getKeywords(user))

    @PostMapping
    fun add(
        @AuthenticationPrincipal user: User,
        @RequestBody req: AddKeywordRequest,
    ): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(service.addKeyword(user, req.keyword))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to (e.message ?: "Ключевое слово уже существует")))
        }
    }

    @DeleteMapping("/{id}")
    fun remove(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.removeKeyword(user, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/generate")
    fun generate(
        @AuthenticationPrincipal user: User,
        @RequestBody req: GenerateKeywordsRequest,
    ): ResponseEntity<*> {

        val plan   = user.subscriptionPlan
        val status = user.subscriptionStatus
        val hasAccess = plan in setOf("MINIMUM", "START") || status == "TRIAL"

        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "AI-генерация ключевых слов доступна на тарифе MINIMUM"))
        }

        if (req.businessContext.isBlank() || req.businessContext.trim().length < 20) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Описание бизнеса слишком короткое (минимум 20 символов)"))
        }

        return try {
            val keywords = aiService.generateKeywords(req.businessContext)
            ResponseEntity.ok(GenerateKeywordsResponse(keywords))
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to "AI-сервис временно недоступен"))
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Ошибка генерации ключевых слов: ${e.message}"))
        }
    }
}


data class SaveBusinessContextRequest(val businessContext: String)

@RestController
@RequestMapping("/api/v1/business-context")
class BusinessContextController(private val service: LeadService) {

    @GetMapping
    fun get(@AuthenticationPrincipal user: User): ResponseEntity<BusinessContextDto> =
        ResponseEntity.ok(service.getBusinessContext(user))

    @PostMapping
    fun save(
        @AuthenticationPrincipal user: User,
        @RequestBody req: SaveBusinessContextRequest,
    ): ResponseEntity<BusinessContextDto> =
        ResponseEntity.ok(service.saveBusinessContext(user, req.businessContext))
}


// ─── Тумблер «реагировать на предложения услуг» ──────────────────────────────

data class ServiceOffersToggleResponse(val respondToServiceOffers: Boolean)

@RestController
@RequestMapping("/api/v1/settings")
class UserSettingsController(
    private val userRepository: UserRepository,
) {

    /**
     * GET /api/v1/settings/service-offers
     * Возвращает текущее значение флага.
     */
    @GetMapping("/service-offers")
    fun getServiceOffers(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<ServiceOffersToggleResponse> =
        ResponseEntity.ok(ServiceOffersToggleResponse(user.respondToServiceOffers))

    /**
     * POST /api/v1/settings/service-offers
     * Body: {"enabled": true}
     * Устанавливает флаг и сохраняет в БД.
     */
    @PostMapping("/service-offers")
    fun setServiceOffers(
        @AuthenticationPrincipal user: User,
        @RequestBody body: Map<String, Boolean>,
    ): ResponseEntity<ServiceOffersToggleResponse> {
        val enabled = body["enabled"] ?: return ResponseEntity.badRequest().build()
        user.respondToServiceOffers = enabled
        userRepository.save(user)
        return ResponseEntity.ok(ServiceOffersToggleResponse(user.respondToServiceOffers))
    }
}


// ─── TGStat — поиск чатов ─────────────────────────────────────────────────────

data class TgstatSearchRequest(
    /** Произвольный запрос от пользователя. Если пустой — используется businessContext профиля. */
    val query: String = "",
)

data class TgstatChannelResult(
    val title: String,
    val username: String?,
    val description: String?,
    val participantsCount: Int,
    val link: String,
    val tgstatLink: String?,
)

data class TgstatSearchResponse(
    val results: List<TgstatChannelResult>,
    val queries: List<String>,
)

@RestController
@RequestMapping("/api/v1/chats/search")
class TgstatSearchController(
    private val aiService: AiService,
    @Value("\${tgstat.api-token:}") private val tgstatToken: String,
) {
    private val log = LoggerFactory.getLogger(TgstatSearchController::class.java)
    private val restTemplate = RestTemplate()

    /**
     * POST /api/v1/chats/search
     * Принимает текстовый запрос (или использует businessContext пользователя),
     * через AI генерирует поисковые запросы и ищет чаты в TGStat.
     * Возвращает до 5 уникальных результатов.
     */
    @PostMapping
    fun search(
        @AuthenticationPrincipal user: User,
        @RequestBody req: TgstatSearchRequest,
    ): ResponseEntity<*> {

        val plan   = user.subscriptionPlan
        val status = user.subscriptionStatus
        val hasAccess = plan in setOf("MINIMUM", "START") || status == "TRIAL"

        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Поиск чатов доступен на тарифе MINIMUM и выше"))
        }

        if (tgstatToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to "TGStat API временно недоступен"))
        }

        // Определяем источник запроса: явный или из профиля
        val inputText = req.query.trim().ifBlank {
            user.businessContext?.trim() ?: ""
        }

        if (inputText.isBlank()) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Введите запрос или заполните AI-профиль в настройках"))
        }

        // AI генерирует 2-4 поисковых запроса
        val queries = aiService.generateTgstatQueries(inputText)
        log.info("TGStat поиск для user=${user.id}: запросы=$queries")

        // Собираем уникальные результаты по всем запросам
        val seen = mutableSetOf<String>()
        val results = mutableListOf<TgstatChannelResult>()

        for (query in queries) {
            if (results.size >= 5) break
            try {
                val found = searchTgstat(query)
                for (ch in found) {
                    val key = ch.username ?: ch.title
                    if (key !in seen) {
                        seen.add(key)
                        results.add(ch)
                        if (results.size >= 5) break
                    }
                }
            } catch (e: Exception) {
                log.warn("TGStat запрос '$query' завершился ошибкой: ${e.message}")
            }
        }

        return ResponseEntity.ok(TgstatSearchResponse(results = results, queries = queries))
    }

    private fun searchTgstat(query: String): List<TgstatChannelResult> {
        val url = "https://api.tgstat.ru/channels/search" +
                "?token=$tgstatToken" +
                "&q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                "&peer_type=chat" +   // только группы/чаты
                "&limit=5" +
                "&extended=1"

        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
        }

        val response = restTemplate.exchange(
            url,
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            TgstatApiResponse::class.java,
        )

        val body = response.body ?: return emptyList()
        if (body.status != "ok") return emptyList()

        return body.response?.items?.map { item ->
            val username = item.username?.let { if (it.startsWith("@")) it else "@$it" }
            val link = when {
                !item.username.isNullOrBlank() -> "https://t.me/${item.username.trimStart('@')}"
                else -> ""
            }
            TgstatChannelResult(
                title            = item.title ?: "Без названия",
                username         = username,
                description      = item.about?.take(200),
                participantsCount = item.participantsCount ?: 0,
                link             = link,
                tgstatLink       = if (!item.username.isNullOrBlank())
                    "https://tgstat.ru/channel/@${item.username.trimStart('@')}"
                else null,
            )
        } ?: emptyList()
    }
}

// ─── TGStat API response models ───────────────────────────────────────────────

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class TgstatApiResponse(
    val status: String = "",
    val response: TgstatResponseBody? = null,
)

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class TgstatResponseBody(
    val count: Int = 0,
    val items: List<TgstatChannelItem>? = null,
)

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class TgstatChannelItem(
    val id: Long = 0,
    val title: String? = null,
    val username: String? = null,
    val about: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("participants_count")
    val participantsCount: Int? = null,
)