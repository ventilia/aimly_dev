package io.getaimly.backend.lead

import io.getaimly.backend.user.User
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*



data class TgstatSearchRequest(
    val query: String = "",
    val peerType: String? = null,
)


data class TgstatChannelResult(
    val title:             String,
    val username:          String?,
    val description:       String?,
    val participantsCount: Int,
    val link:              String,
    val peerType:          String = "chat",
)

data class TgstatSearchResponse(
    val results: List<TgstatChannelResult>,
    val queries: List<String>,
)



@RestController
@RequestMapping("/api/v1/chats/search")
class TgstatSearchController(
    private val chatSearchService: ChatSearchService,
) {
    private val log = LoggerFactory.getLogger(TgstatSearchController::class.java)

    @PostMapping
    fun search(
        @AuthenticationPrincipal user: User,
        @RequestBody req: TgstatSearchRequest,
    ): ResponseEntity<*> {

        val plan      = user.subscriptionPlan
        val status    = user.subscriptionStatus
        val hasAccess = plan in setOf("START", "BUSINESS") || status == "TRIAL"
        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Поиск чатов доступен на тарифе START и выше"))
        }

        val inputText = req.query.trim().ifBlank { user.businessContext?.trim() ?: "" }
        if (inputText.isBlank()) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Введите запрос или заполните AI-профиль в настройках"))
        }

        // Нормализуем peerType
        val peerType = when (req.peerType?.lowercase()?.trim()) {
            "chat"    -> "chat"
            "channel" -> "channel"
            else      -> null
        }

        return try {
            log.info("поиск чатов: userId=${user.id} query='${inputText.take(80)}' peerType=${peerType ?: "all"}")
            val searchResult = chatSearchService.search(inputText, peerType)
            ResponseEntity.ok(
                TgstatSearchResponse(
                    results = searchResult.results.map { it.toPublicDto() },
                    queries = searchResult.queries,
                )
            )
        } catch (e: IllegalStateException) {
            log.error("chatSearchService.search ошибка конфигурации: ${e.message}")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to (e.message ?: "Сервис поиска недоступен")))
        } catch (e: Exception) {
            log.error("chatSearchService.search неожиданная ошибка: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Ошибка поиска. Попробуйте позже."))
        }
    }

    private fun ChatSearchResult.toPublicDto() = TgstatChannelResult(
        title             = title,
        username          = username,
        description       = description,
        participantsCount = participantsCount,
        link              = link,
        peerType          = peerType,
    )
}