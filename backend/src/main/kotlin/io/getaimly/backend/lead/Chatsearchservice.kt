package io.getaimly.backend.lead

import io.getaimly.backend.ai.AiService
import io.getaimly.backend.ai.GroqResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

// ─── DTOs ─────────────────────────────────────────────────────────────────────

data class ChatSearchResult(
    val title: String,
    val username: String?,
    val description: String?,
    val participantsCount: Int,
    val link: String,
    val tgstatLink: String?,
    val peerType: String = "chat",
)

data class ChatSearchResponse(
    val results: List<ChatSearchResult>,
    val queries: List<String>,
)

// ─── Service ──────────────────────────────────────────────────────────────────

@Service
class ChatSearchService(
    private val aiService: AiService,
    @Value("\${tgstat.api-key:}") private val tgstatApiKey: String,
    @Value("\${tgstat.country:ru}") private val tgstatCountry: String,
    @Value("\${groq.api-key:}") private val groqApiKey: String,
) {
    private val log = LoggerFactory.getLogger(ChatSearchService::class.java)
    private val restTemplate = RestTemplate()

    private val MIN_MEMBERS    = 600
    private val TGSTAT_LANGUAGE = "russian"

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

    private val FIXED_DESIGN_CHAT = ChatSearchResult(
        title             = "Designgang Chat",
        username          = "@desgangchat",
        description       = "Чат для дизайнеров — обсуждение работ, вакансии и заказы",
        participantsCount = 0,
        link              = "https://t.me/desgangchat",
        tgstatLink        = null,
        peerType          = "chat",
    )

    private val FIXED_EVENT_CHAT = ChatSearchResult(
        title             = "MSK Event Job",
        username          = "@mskeventjob",
        description       = "Вакансии и заказы для творческих специальностей: event, фото, видео, дизайн",
        participantsCount = 0,
        link              = "https://t.me/mskeventjob",
        tgstatLink        = null,
        peerType          = "chat",
    )

    private fun isDesignRelated(input: String)   = DESIGN_SIGNALS.any   { input.lowercase().contains(it) }
    private fun isCreativeRelated(input: String) = CREATIVE_SIGNALS.any { input.lowercase().contains(it) }

    // ─── Стоп-слова для фильтрации core-терминов ─────────────────────────────

    private val STOP_WORDS = setOf(
        "вакансии", "вакансия", "jobs", "job", "фриланс", "freelance",
        "биржа", "работа", "найти", "поиск", "search",
    )

    private fun extractCoreTerms(queries: List<String>): Set<String> =
        queries
            .flatMap { it.lowercase().split("\\s+".toRegex()) }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    private fun isTitleRelevant(result: ChatSearchResult, coreTerms: Set<String>): Boolean {
        if (coreTerms.isEmpty()) return true
        val title    = result.title.lowercase()
        val username = result.username?.lowercase() ?: ""
        return coreTerms.any { term -> title.contains(term) || username.contains(term) }
    }

    // ─── Main search entry point ──────────────────────────────────────────────

    /**
     * Полный цикл поиска чатов:
     * 1. Генерируем поисковые запросы через AI
     * 2. Ищем по TGStat (3 прохода)
     * 3. Добавляем фиксированные чаты
     * 4. Фильтруем через второй AI-слой (удаляем нерелевантные, NSFW, спам)
     */
    fun search(inputText: String): ChatSearchResponse {
        val queries   = aiService.generateTgstatQueries(inputText)
        log.info("TGStat поиск: запросы=$queries")

        val coreTerms = extractCoreTerms(queries)
        log.info("TGStat core terms: $coreTerms")

        val seen    = mutableSetOf<String>()
        val results = mutableListOf<ChatSearchResult>()

        // ─── Проход 1: поиск только по названию ──────────────────────────────
        for (query in queries) {
            if (results.size >= 7) break
            addUnique(searchTgstat(query, limit = 20, searchByDescription = false), seen, results)
        }

        // ─── Проход 2: поиск с описанием, если мало результатов ──────────────
        if (results.size < 4 && coreTerms.isNotEmpty()) {
            for (query in queries.take(3)) {
                if (results.size >= 7) break
                val raw      = searchTgstat(query, limit = 40, searchByDescription = true)
                val filtered = raw.filter { isTitleRelevant(it, coreTerms) }
                log.info("TGStat desc '$query': всего=${raw.size} после фильтра=${filtered.size}")
                addUnique(filtered, seen, results)
            }
        }

        // ─── Проход 3: fallback по core-терминам ─────────────────────────────
        if (results.isEmpty()) {
            val fallback = coreTerms.filter { it.length >= 4 }.sortedByDescending { it.length }.take(3)
            log.info("TGStat fallback: $fallback")
            for (query in fallback) {
                if (results.size >= 5) break
                addUnique(searchTgstat(query, limit = 50, searchByDescription = false), seen, results, maxResults = 5)
            }
        }

        // ─── Фиксированные чаты ──────────────────────────────────────────────
        if (isDesignRelated(inputText) && results.none { it.link.contains("desgangchat") }) {
            results.add(FIXED_DESIGN_CHAT); seen.add("desgangchat")
        }
        if (isCreativeRelated(inputText) && results.none { it.link.contains("mskeventjob") }) {
            results.add(FIXED_EVENT_CHAT); seen.add("mskeventjob")
        }

        log.info("TGStat до AI-фильтра: ${results.size} чатов")

        // ─── Второй AI-слой: фильтрация каналов ──────────────────────────────
        val filtered = if (results.isNotEmpty()) {
            validateChannels(results, inputText)
        } else {
            results
        }

        log.info("TGStat после AI-фильтра: ${filtered.size} чатов (удалено ${results.size - filtered.size})")

        return ChatSearchResponse(results = filtered, queries = queries)
    }

    // ─── Второй AI-слой: валидация релевантности каналов ─────────────────────

    /**
     * Проверяет список найденных каналов через Groq.
     * Удаляет нерелевантные, NSFW/эротику, спам, крипто-скамы,
     * каналы без обсуждений (только новости) и не по теме запроса.
     *
     * Работает батчами по 10 каналов, чтобы не превышать лимиты Groq.
     */
    fun validateChannels(channels: List<ChatSearchResult>, query: String): List<ChatSearchResult> {
        if (groqApiKey.isBlank() || channels.isEmpty()) return channels

        val result = mutableListOf<ChatSearchResult>()
        val chunkSize = 10

        channels.chunked(chunkSize).forEach { chunk ->
            val keepIndices = validateChannelsBatch(chunk, query)
            chunk.forEachIndexed { idx, ch ->
                if (idx in keepIndices) result.add(ch)
            }
        }

        return result
    }

    private fun validateChannelsBatch(channels: List<ChatSearchResult>, query: String): Set<Int> {
        if (groqApiKey.isBlank()) return channels.indices.toSet()

        val channelList = channels.mapIndexed { idx, ch ->
            val members = if (ch.participantsCount > 0) " | Участников: ${ch.participantsCount}" else ""
            val desc    = if (!ch.description.isNullOrBlank()) " | Описание: ${ch.description.take(120)}" else ""
            "${idx + 1}. «${ch.title}» (@${ch.username ?: "нет"})$members$desc"
        }.joinToString("\n")

        val prompt = """
Ты помогаешь системе поиска ЛИДОВ и КЛИЕНТОВ в Telegram.
Пользователь — предприниматель или фрилансер, который ищет чаты, где сидит его целевая аудитория (потенциальные заказчики, клиенты, покупатели).

Запрос пользователя: "$query"

Список найденных Telegram-каналов/чатов:
$channelList

Оцени каждый чат: KEEP (оставить) или REMOVE (удалить).

REMOVE только если канал явно содержит:
- Эротику, 18+, adult/NSFW контент
- Казино, ставки, букмекеры
- Крипто-скамы, финансовые пирамиды, обман
- Тематика кардинально не совпадает с запросом (например, запрос про дизайн, а канал про рыбалку)

KEEP во всех остальных случаях, включая:
- Профессиональные чаты и каналы по теме (даже если уклон в вакансии/работу — там тоже бывают заказы)
- Чаты фрилансеров, биржи заказов, чаты с поиском специалистов — они ОСОБЕННО ценны
- Новостные каналы по теме — там может быть аудитория
- Чаты, косвенно связанные с темой (смежные ниши)

Важно: лучше оставить лишний чат, чем удалить нужный. Будь ЩЕДРЫМ с KEEP.

Ответь строго JSON без пояснений и markdown:
{"keep": [1, 3, 5], "remove": [2, 4]}
""".trimIndent()

        return try {
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(groqApiKey)
            }

            val body = mapOf(
                "model"    to "llama-3.1-8b-instant",
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "Отвечай только JSON. Никаких пояснений, никаких markdown-блоков."),
                    mapOf("role" to "user",   "content" to prompt),
                ),
                "max_tokens" to 200,
                "temperature" to 0.0,
            )

            val resp = restTemplate.postForObject(
                "https://api.groq.com/openai/v1/chat/completions",
                HttpEntity(body, headers),
                GroqResponse::class.java,
            ) ?: return channels.indices.toSet()

            val raw   = resp.choices.firstOrNull()?.message?.content ?: return channels.indices.toSet()
            val clean = raw.replace(Regex("```json|```"), "").trim()

            // Парсим {"keep": [1,2,3]}
            val keepJson = Regex("\"keep\"\\s*:\\s*\\[([^]]*)]")
                .find(clean)?.groupValues?.get(1) ?: return channels.indices.toSet()

            val keepIndices = Regex("\\d+")
                .findAll(keepJson)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..channels.size }
                .map { it - 1 }  // 1-based → 0-based
                .toSet()

            // Safety threshold: если AI оставил меньше 40% — что-то пошло не так,
            // возвращаем все каналы без фильтрации (fail-open).
            if (keepIndices.size < channels.size * 0.4 && channels.size >= 3) {
                log.warn("validateChannelsBatch: AI оставил слишком мало (${keepIndices.size}/${channels.size}), возвращаем все без фильтра")
                return channels.indices.toSet()
            }

            log.info("AI канал-фильтр: оставляем $keepIndices из ${channels.size}")
            keepIndices

        } catch (e: Exception) {
            log.warn("validateChannelsBatch ошибка: ${e.message} — возвращаем всё без фильтра")
            channels.indices.toSet()
        }
    }

    // ─── TGStat запрос ────────────────────────────────────────────────────────

    private fun searchTgstat(
        query: String,
        limit: Int = 20,
        searchByDescription: Boolean = false,
    ): List<ChatSearchResult> {
        if (query.length < 2) {
            log.warn("TGStat: пропускаем короткий запрос '$query'")
            return emptyList()
        }

        val url = buildString {
            append("https://api.tgstat.ru/channels/search")
            append("?token=${java.net.URLEncoder.encode(tgstatApiKey, "UTF-8")}")
            append("&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            append("&country=${java.net.URLEncoder.encode(tgstatCountry, "UTF-8")}")
            append("&language=${java.net.URLEncoder.encode(TGSTAT_LANGUAGE, "UTF-8")}")
            append("&peer_type=all")
            if (searchByDescription) append("&search_by_description=1")
            append("&limit=$limit")
        }

        log.info("TGStat запрос: q='$query' country=$tgstatCountry limit=$limit desc=$searchByDescription")

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

                ChatSearchResult(
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

    private fun addUnique(
        found: List<ChatSearchResult>,
        seen: MutableSet<String>,
        results: MutableList<ChatSearchResult>,
        maxResults: Int = 7,
    ) {
        for (ch in found) {
            if (results.size >= maxResults) break
            val key = ch.username?.lowercase() ?: ch.title.lowercase()
            if (seen.add(key)) results.add(ch)
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