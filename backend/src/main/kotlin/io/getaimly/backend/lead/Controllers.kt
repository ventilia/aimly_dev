package io.getaimly.backend.lead

import io.getaimly.backend.ai.AiService
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*


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

    @PostMapping("/read-all")
    fun markAllRead(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<Void> {
        service.markAllRead(user)
        return ResponseEntity.noContent().build()
    }
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
    private val service:   LeadService,
    private val aiService: AiService,
) {
    private val log = LoggerFactory.getLogger(KeywordController::class.java)

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

        val plan      = user.subscriptionPlan
        val status    = user.subscriptionStatus
        val hasAccess = plan in setOf("START", "BUSINESS") || status == "TRIAL"

        if (!hasAccess) {
            log.warn("[KEYWORDS] Нет доступа к AI-генерации: userId=${user.id} email=${user.email} plan=$plan status=$status")
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "AI-генерация ключевых слов доступна на тарифе START"))
        }

        if (req.businessContext.isBlank() || req.businessContext.trim().length < 20) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Описание бизнеса слишком короткое (минимум 20 символов)"))
        }

        log.info("[KEYWORDS] AI-генерация запрошена: userId=${user.id} email=${user.email} контекст=\"${req.businessContext.trim().take(80)}\"")

        return try {
            val keywords = aiService.generateKeywords(req.businessContext)
            log.info("[KEYWORDS] AI-генерация выполнена: userId=${user.id} email=${user.email} сгенерировано=${keywords.size}")
            ResponseEntity.ok(GenerateKeywordsResponse(keywords))
        } catch (e: IllegalStateException) {
            log.error("AI-генерация: сервис недоступен для userId=${user.id}: ${e.message}")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to "AI-сервис временно недоступен"))
        } catch (e: Exception) {
            log.error("AI-генерация: ошибка для userId=${user.id}: ${e.message}")
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


data class ServiceOffersToggleResponse(val respondToServiceOffers: Boolean)

@RestController
@RequestMapping("/api/v1/settings")
class UserSettingsController(
    private val userRepository: UserRepository,
) {
    private val log = LoggerFactory.getLogger(UserSettingsController::class.java)

    @GetMapping("/service-offers")
    fun getServiceOffers(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<ServiceOffersToggleResponse> {
        val fresh = userRepository.findById(user.id).orElse(user)
        return ResponseEntity.ok(ServiceOffersToggleResponse(fresh.respondToServiceOffers))
    }

    @PostMapping("/service-offers")
    @Transactional
    fun setServiceOffers(
        @AuthenticationPrincipal user: User,
        @RequestBody body: Map<String, Boolean>,
    ): ResponseEntity<ServiceOffersToggleResponse> {
        val enabled = body["enabled"] ?: return ResponseEntity.badRequest().build()

        val managedUser = userRepository.findById(user.id)
            .orElse(null) ?: return ResponseEntity.notFound().build()

        managedUser.respondToServiceOffers = enabled
        userRepository.save(managedUser)

        log.info("[SETTINGS] AI service-offers: userId=${user.id} email=${user.email} enabled=$enabled")
        return ResponseEntity.ok(ServiceOffersToggleResponse(managedUser.respondToServiceOffers))
    }
}