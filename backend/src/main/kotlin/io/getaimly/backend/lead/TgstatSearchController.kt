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

    private val MIN_MEMBERS = 600

    // ─── Фиксированные рекомендованные чаты ──────────────────────────────────

    private val DESIGN_SIGNALS = setOf(
        "дизайн", "design", "ui", "ux", "graphic", "график", "иллюстр",
        "брендинг", "figma", "photoshop", "illustrator", "sketch", "motion", "моушн",
    )

    private val CREATIVE_SIGNALS = setOf(
        "дизайн", "design", "фото", "photo", "видео", "video", "арт", "art",
        "event", "ивент", "мероприят", "свадьб", "wedding",
        "съёмк", "оператор", "продакшн", "production", "стилист", "визаж", "флорист",
    )

    private val FIXED_DESIGN_CHAT = TgstatChannelResult(
        title             = "Designgang Chat",
        username          = "@desgangchat",
        description       = "Чат для дизайнеров — обсуждение работ, вакансии и заказы",
        participantsCount = 0,
        link              = "https://t.me/desgangchat",
        tgstatLink        = null,
        peerType          = "chat",
    )

    private val FIXED_EVENT_CHAT = TgstatChannelResult(
        title             = "MSK Event Job",
        username          = "@mskeventjob",
        description       = "Вакансии и заказы для творческих специальностей: event, фото, видео, дизайн",
        participantsCount = 0,
        link              = "https://t.me/mskeventjob",
        tgstatLink        = null,
        peerType          = "chat",
    )

    private fun isDesignRelated(input: String): Boolean =
        DESIGN_SIGNALS.any { input.lowercase().contains(it) }

    private fun isCreativeRelated(input: String): Boolean =
        CREATIVE_SIGNALS.any { input.lowercase().contains(it) }

    // ─── Релевантность ────────────────────────────────────────────────────────

    // Стоп-слова — слишком общие, их не используем для фильтра по title
    private val STOP_WORDS = setOf(
        "вакансии", "вакансия", "jobs", "job", "фриланс", "freelance",
        "биржа", "работа", "найти", "поиск", "search",
    )

    /**
     * Извлекает значимые термины из AI-запросов для фильтрации результатов.
     * При поиске с search_by_description=1 результат принимается, только если
     * хотя бы один core-термин присутствует в названии или username.
     */
    private fun extractCoreTerms(queries: List<String>): Set<String> =
        queries
            .flatMap { it.lowercase().split("\\s+".toRegex()) }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    private fun isTitleRelevant(result: TgstatChannelResult, coreTerms: Set<String>): Boolean {
        if (coreTerms.isEmpty()) return true
        val title    = result.title.lowercase()
        val username = result.username?.lowercase() ?: ""
        return coreTerms.any { term -> title.contains(term) || username.contains(term) }
    }

    // ─── Search endpoint ──────────────────────────────────────────────────────

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

        val queries = aiService.generateTgstatQueries(inputText)
        log.info("TGStat поиск для user=${user.id}: запросы=$queries")

        val coreTerms = extractCoreTerms(queries)
        log.info("TGStat core terms: $coreTerms")

        val seen    = mutableSetOf<String>()
        val results = mutableListOf<TgstatChannelResult>()

        // ─── Проход 1: поиск ТОЛЬКО по названию, peer_type=all ───────────────
        // peer_type=chat + country=ru возвращает 0 результатов для большинства
        // русских запросов — TGStat плохо индексирует группы.
        // Используем peer_type=all и убираем country=ru для русских запросов.
        for (query in queries) {
            if (results.size >= 7) break
            addUnique(
                searchTgstat(query, limit = 20, searchByDescription = false),
                seen, results, maxResults = 7,
            )
        }

        // ─── Проход 2: поиск с описанием, только если мало результатов ───────
        // Берём первые 3 запроса (наиболее точные — с "вакансии"/"jobs" и чистые).
        // Каждый результат фильтруется: title должен содержать core-термин.
        if (results.size < 4 && coreTerms.isNotEmpty()) {
            for (query in queries.take(3)) {
                if (results.size >= 7) break
                val raw      = searchTgstat(query, limit = 40, searchByDescription = true)
                val filtered = raw.filter { isTitleRelevant(it, coreTerms) }
                log.info("TGStat desc '$query': всего=${raw.size} после фильтра=${filtered.size}")
                addUnique(filtered, seen, results, maxResults = 7)
            }
        }

        // ─── Проход 3: fallback — только core-термины без служебных слов ─────
        if (results.isEmpty()) {
            val fallback = coreTerms.filter { it.length >= 4 }.sortedByDescending { it.length }.take(3)
            log.info("TGStat fallback для user=${user.id}: $fallback")
            for (query in fallback) {
                if (results.size >= 5) break
                addUnique(
                    searchTgstat(query, limit = 50, searchByDescription = false),
                    seen, results, maxResults = 5,
                )
            }
        }

        // ─── Фиксированные рекомендованные чаты ──────────────────────────────
        if (isDesignRelated(inputText) && results.none { it.link.contains("desgangchat") }) {
            results.add(FIXED_DESIGN_CHAT)
            seen.add("desgangchat")
            log.info("TGStat: добавлен desgangchat для user=${user.id}")
        }
        if (isCreativeRelated(inputText) && results.none { it.link.contains("mskeventjob") }) {
            results.add(FIXED_EVENT_CHAT)
            seen.add("mskeventjob")
            log.info("TGStat: добавлен mskeventjob для user=${user.id}")
        }

        log.info("TGStat итог для user=${user.id}: найдено ${results.size} результатов")
        return ResponseEntity.ok(TgstatSearchResponse(results = results, queries = queries))
    }

    private fun addUnique(
        found: List<TgstatChannelResult>,
        seen: MutableSet<String>,
        results: MutableList<TgstatChannelResult>,
        maxResults: Int = 7,
    ) {
        for (ch in found) {
            if (results.size >= maxResults) break
            val key = ch.username?.lowercase() ?: ch.title.lowercase()
            if (seen.add(key)) results.add(ch)
        }
    }

    /**
     * Запрос к TGStat API.
     *
     * Ключевые изменения по сравнению с предыдущей версией:
     * - peer_type не передаём (дефолт API = all) — peer_type=chat + country=ru
     *   возвращал 0 результатов для большинства русских запросов
     * - country=ru не передаём — ограничивает выдачу и не нужен, т.к. запросы
     *   на русском языке уже фильтруют русские чаты
     * - language не передаём — дефолт API = "russian", явная передача → ошибка
     * - searchByDescription=false (дефолт): только по названию → чистые результаты
     * - searchByDescription=true: по описанию, но используем только с isTitleRelevant
     */
    private fun searchTgstat(
        query: String,
        limit: Int = 20,
        searchByDescription: Boolean = false,
    ): List<TgstatChannelResult> {
        if (query.length < 2) {
            log.warn("TGStat: пропускаем короткий запрос '$query'")
            return emptyList()
        }

        val url = buildString {
            append("https://api.tgstat.ru/channels/search")
            append("?token=${java.net.URLEncoder.encode(tgstatApiKey, "UTF-8")}")
            append("&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            if (searchByDescription) append("&search_by_description=1")
            append("&limit=$limit")
        }

        log.info("TGStat запрос: q='$query' limit=$limit desc=$searchByDescription")

        return try {
            val headers  = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(headers), TgstatApiResponse::class.java,
            )
            val body = response.body ?: run {
                log.warn("TGStat: пустое тело для '$query'")
                return emptyList()
            }

            if (body.status != "ok") {
                log.error("TGStat: статус='${body.status}' для '$query'. error=${body.error}")
                return emptyList()
            }

            val items = body.response?.items ?: emptyList()
            log.info("TGStat '$query': ${items.size} сырых результатов")

            items.mapNotNull { item ->
                val rawUsername   = item.username?.takeIf { it.isNotBlank() }
                val cleanUsername = rawUsername?.let { if (it.startsWith("@")) it else "@$it" }

                val apiLink = item.link?.takeIf { it.isNotBlank() }
                val link = when {
                    apiLink != null     -> if (apiLink.startsWith("https://")) apiLink else "https://$apiLink"
                    rawUsername != null -> "https://t.me/${rawUsername.trimStart('@')}"
                    else                -> return@mapNotNull null
                }

                val members = item.participantsCount ?: 0
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
                    peerType          = item.peerType ?: "channel",
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