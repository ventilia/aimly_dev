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
data class TgstatSearchRequest(val query: String = "")

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
    @Value("\${tgstat.api-key:}") private val tgstatApiKey: String,
) {
    private val log = LoggerFactory.getLogger(TgstatSearchController::class.java)
    private val restTemplate = RestTemplate()

    // Минимум участников — чаты с меньшим числом не возвращаем
    private val MIN_MEMBERS = 600

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
        if (tgstatApiKey.isBlank()) {
            log.error("TGStat API ключ не настроен")
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to "TGStat API ключ не настроен"))
        }

        val inputText = req.query.trim().ifBlank { user.businessContext?.trim() ?: "" }
        if (inputText.isBlank()) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Введите запрос или заполните AI-профиль в настройках"))
        }

        // AI генерирует запросы в приоритетном порядке:
        // 1. тема + вакансии (рус), 2. тема + jobs (eng),
        // 3. просто тема (рус), 4. просто тема (eng), 5. смежное
        val queries = aiService.generateTgstatQueries(inputText)
        log.info("TGStat поиск для user=${user.id}: запросы=$queries")

        val seen    = mutableSetOf<String>()
        val results = mutableListOf<TgstatChannelResult>()

        // Проход 1: только чаты (peer_type=chat) в порядке приоритетных запросов
        for (query in queries) {
            if (results.size >= 5) break
            addUnique(searchTgstat(query, peerType = "chat", limit = 20), seen, results)
        }

        // Проход 2: все типы (chat + channel), дополняем до 5
        if (results.size < 5) {
            for (query in queries) {
                if (results.size >= 5) break
                addUnique(searchTgstat(query, peerType = "all", limit = 20), seen, results)
            }
        }

        // Проход 3: fallback — первые слова запросов, увеличенный limit
        if (results.isEmpty()) {
            val fallback = queries
                .map { it.trim().split("\\s+".toRegex()).first().lowercase() }
                .filter { it.length >= 3 }
                .distinct()
            log.info("TGStat fallback для user=${user.id}: $fallback")
            for (query in fallback) {
                if (results.size >= 5) break
                addUnique(searchTgstat(query, peerType = "all", limit = 50), seen, results)
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
     *
     * Правила:
     * - token    — обязателен (НЕ key)
     * - country  — обязателен (ru)
     * - language — НЕ передаём: дефолт в API = "russian", любая явная передача вызывает wrong_language_param
     * - peer_type = "chat" для чатов, "all" для fallback
     */
    private fun searchTgstat(
        query: String,
        peerType: String = "chat",
        limit: Int = 20,
    ): List<TgstatChannelResult> {
        if (query.length < 3) {
            log.warn("TGStat: пропускаем короткий запрос '$query'")
            return emptyList()
        }

        val url = buildString {
            append("https://api.tgstat.ru/channels/search")
            append("?token=${java.net.URLEncoder.encode(tgstatApiKey, "UTF-8")}")
            append("&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            append("&peer_type=$peerType")
            append("&country=ru")
            append("&search_by_description=1")
            append("&limit=$limit")
        }

        log.info("TGStat запрос: q='$query' peer_type=$peerType limit=$limit")

        return try {
            val headers = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
            val response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), TgstatApiResponse::class.java)
            val body = response.body ?: run { log.warn("TGStat: пустое тело для '$query'"); return emptyList() }

            if (body.status != "ok") {
                log.error("TGStat: статус='${body.status}' для '$query'. error=${body.error}")
                return emptyList()
            }

            val items = body.response?.items ?: emptyList()
            log.info("TGStat '$query' peer=$peerType: получено ${items.size} результатов")

            items.mapNotNull { item ->
                val rawUsername   = item.username?.takeIf { it.isNotBlank() }
                val cleanUsername = rawUsername?.let { if (it.startsWith("@")) it else "@$it" }

                val apiLink = item.link?.takeIf { it.isNotBlank() }
                val link = when {
                    apiLink != null    -> if (apiLink.startsWith("https://")) apiLink else "https://$apiLink"
                    rawUsername != null -> "https://t.me/${rawUsername.trimStart('@')}"
                    else               -> return@mapNotNull null
                }

                val members = item.participantsCount ?: 0

                // Фильтр: не возвращаем чаты с менее чем MIN_MEMBERS участников
                // (0 означает что данные не пришли — пропускаем проверку)
                if (members in 1 until MIN_MEMBERS) return@mapNotNull null

                val tgstatLink = rawUsername?.let { slug ->
                    val s = slug.trimStart('@')
                    if (item.peerType == "chat") "https://tgstat.ru/chat/$s"
                    else "https://tgstat.ru/channel/$s"
                }

                TgstatChannelResult(
                    title             = item.title?.ifBlank { cleanUsername ?: "Без названия" } ?: cleanUsername ?: "Без названия",
                    username          = cleanUsername,
                    description       = item.about?.trim()?.take(200)?.ifBlank { null },
                    participantsCount = members,
                    link              = link,
                    tgstatLink        = tgstatLink,
                    peerType          = item.peerType ?: peerType,
                )
            }
        } catch (e: HttpClientErrorException) {
            log.error("TGStat HTTP ${e.statusCode} для '$query': ${e.responseBodyAsString}")
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
    val error: String? = null,
    val message: String? = null,
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
    val link: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("participants_count")
    val participantsCount: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("peer_type")
    val peerType: String? = null,
)