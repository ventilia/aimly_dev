package io.getaimly.backend.lead

import io.getaimly.backend.user.User
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
}

// го

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
            // Дубликат чата
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

@RestController
@RequestMapping("/api/v1/keywords")
class KeywordController(private val service: LeadService) {

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
            // Дубликат ключевого слова
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