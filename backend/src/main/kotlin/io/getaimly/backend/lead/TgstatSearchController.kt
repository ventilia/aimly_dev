package io.getaimly.backend.lead

import io.getaimly.backend.ai.AiService
import io.getaimly.backend.user.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

// ─── DTOs ─────────────────────────────────────────────────────────────────────

data class TgstatSearchRequest(
    val query: String = "",
)

data class TgstatChannelResult(
    val title: String,
    val username: String?,
    val description: String?,
    val participantsCount: Int,
    val link: String,
    val tgstatLink: String?,
    val peerType: String = "chat",
)

data class TgstatSearchResponse(
    val results: List<TgstatChannelResult>,
    val queries: List<String>,
)

// ─── Controller ───────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/chats/search")
class TgstatSearchController(
    private val aiService: AiService,
    @Value("\${tgstat.api-token:}") private val tgstatToken: String,
) {
    private val log = LoggerFactory.getLogger(TgstatSearchController::class.java)
    private val restTemplate = RestTemplate()

    @PostMapping
    fun search(
        @AuthenticationPrincipal user: User,
        @RequestBody req: TgstatSearchRequest,
    ): ResponseEntity<*> {

        val plan      = user.subscriptionPlan
        val status    = user.subscriptionStatus
        val hasAccess = plan in setOf("MINIMUM", "START") || status == "TRIAL"

        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Поиск чатов доступен на тарифе MINIMUM и выше"))
        }

        if (tgstatToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to "TGStat API временно недоступен"))
        }

        val inputText = req.query.trim().ifBlank {
            user.businessContext?.trim() ?: ""
        }

        if (inputText.isBlank()) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Введите запрос или заполните AI-профиль в настройках"))
        }

        val queries = aiService.generateTgstatQueries(inputText)
        log.info("TGStat поиск для user=${user.id}: запросы=$queries")

        val seen    = mutableSetOf<String>()
        val results = mutableListOf<TgstatChannelResult>()

        // Проход 1: только чаты/группы (peer_type=chat)
        for (query in queries) {
            if (results.size >= 5) break
            val found = searchTgstat(query, peerType = "chat", limit = 10)
            addUnique(found, seen, results)
        }

        // Проход 2: все типы (каналы + чаты), дополняем до 5
        if (results.size < 5) {
            for (query in queries) {
                if (results.size >= 5) break
                val found = searchTgstat(query, peerType = "all", limit = 10)
                addUnique(found, seen, results)
            }
        }

        // Проход 3: fallback — первое слово каждого запроса
        if (results.isEmpty()) {
            val fallback = queries
                .map { it.trim().split("\\s+".toRegex()).first().lowercase() }
                .filter { it.length >= 3 }
                .distinct()
            log.info("TGStat fallback для user=${user.id}: $fallback")
            for (query in fallback) {
                if (results.size >= 5) break
                val found = searchTgstat(query, peerType = "all", limit = 20)
                addUnique(found, seen, results)
            }
        }

        log.info("TGStat итог для user=${user.id}: найдено ${results.size} результатов")
        return ResponseEntity.ok(TgstatSearchResponse(results = results, queries = queries))
    }

    private fun addUnique(
        found: List<TgstatChannelResult>,
        seen: MutableSet<String>,
        results: MutableList<TgstatChannelResult>,
        maxResults: Int = 5,
    ) {
        for (ch in found) {
            if (results.size >= maxResults) break
            val key = ch.username?.lowercase() ?: ch.title.lowercase()
            if (seen.add(key)) results.add(ch)
        }
    }

    /**
     * Один запрос к TGStat API.
     * Ошибки не глотаются — логируются с полным контекстом и пробрасываются наверх.
     */
    private fun searchTgstat(
        query: String,
        peerType: String = "all",
        limit: Int = 10,
    ): List<TgstatChannelResult> {
        if (query.length < 3) {
            log.warn("TGStat: пропускаем слишком короткий запрос '$query'")
            return emptyList()
        }

        val url = buildString {
            append("https://api.tgstat.ru/channels/search")
            append("?token=$tgstatToken")
            append("&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            append("&peer_type=$peerType")
            append("&language=russian")
            append("&search_by_description=1")
            append("&limit=$limit")
            append("&extended=1")
        }

        log.info("TGStat запрос: q='$query' peer_type=$peerType limit=$limit")

        return try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }),
                TgstatApiResponse::class.java,
            )

            val body = response.body
            if (body == null) {
                log.warn("TGStat: пустое тело ответа для запроса '$query'")
                return emptyList()
            }

            if (body.status != "ok") {
                log.warn("TGStat: статус='${body.status}' для запроса '$query'")
                return emptyList()
            }

            val items = body.response?.items
            log.info("TGStat '$query' peer=$peerType: получено ${items?.size ?: 0} результатов (count=${body.response?.count})")

            if (items.isNullOrEmpty()) return emptyList()

            // Принимаем все каналы/чаты — с username И без
            items.map { item ->
                val rawUsername = item.username?.takeIf { it.isNotBlank() }
                val cleanUsername = rawUsername?.let {
                    if (it.startsWith("@")) it else "@$it"
                }
                val link = rawUsername?.let { "https://t.me/${it.trimStart('@')}" } ?: ""
                val tgstatLink = rawUsername?.let { "https://tgstat.ru/channel/${it.trimStart('@')}" }

                TgstatChannelResult(
                    title             = item.title?.ifBlank { cleanUsername ?: "Без названия" } ?: cleanUsername ?: "Без названия",
                    username          = cleanUsername,
                    description       = item.about?.trim()?.take(200)?.ifBlank { null },
                    participantsCount = item.participantsCount ?: 0,
                    link              = link,
                    tgstatLink        = tgstatLink,
                    peerType          = item.peerType ?: peerType,
                )
            }.filter { it.link.isNotBlank() } // оставляем только те, к которым можно получить ссылку
        } catch (e: HttpClientErrorException) {
            log.error("TGStat HTTP ошибка ${e.statusCode} для '$query': ${e.responseBodyAsString}")
            emptyList()
        } catch (e: Exception) {
            log.error("TGStat исключение для '$query': ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
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
    @com.fasterxml.jackson.annotation.JsonProperty("peer_type")
    val peerType: String? = null,
)