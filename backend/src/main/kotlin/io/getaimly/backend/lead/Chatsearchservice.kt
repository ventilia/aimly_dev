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


@Service
class ChatSearchService(
    private val aiService: AiService,
    @Value("\${tgstat.api-key:}") private val tgstatApiKey: String,
    @Value("\${tgstat.country:ru}") private val tgstatCountry: String,
    @Value("\${groq.api-key:}") private val groqApiKey: String,
) {
    private val log = LoggerFactory.getLogger(ChatSearchService::class.java)
    private val restTemplate = RestTemplate()

    private val MIN_MEMBERS = 200



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
        title = "Designgang Chat", username = "@desgangchat",
        description = "Чат для дизайнеров — обсуждение работ, вакансии и заказы",
        participantsCount = 0, link = "https://t.me/desgangchat", tgstatLink = null, peerType = "chat",
    )

    private val FIXED_EVENT_CHAT = ChatSearchResult(
        title = "MSK Event Job", username = "@mskeventjob",
        description = "Вакансии и заказы для творческих специальностей",
        participantsCount = 0, link = "https://t.me/mskeventjob", tgstatLink = null, peerType = "chat",
    )

    private fun isDesignRelated(input: String)   = DESIGN_SIGNALS.any { input.lowercase().contains(it) }
    private fun isCreativeRelated(input: String) = CREATIVE_SIGNALS.any { input.lowercase().contains(it) }


    fun search(inputText: String): ChatSearchResponse {
        val queries = aiService.generateTgstatQueries(inputText)
        log.info("TGStat поиск: запросы=$queries")

        val seen    = mutableSetOf<String>()
        val results = mutableListOf<ChatSearchResult>()


        for (query in queries) {
            if (results.size >= 20) break
            val found = searchTgstat(query, limit = 50, peerType = "chat")
            for (r in found) {
                val key = r.username?.lowercase() ?: continue
                if (seen.add(key)) results.add(r)
            }
            log.info("TGStat '$query' (chat): нашли ${found.size}, итого уникальных: ${results.size}")
        }


        if (results.size < 5) {
            log.info("TGStat: мало результатов (${results.size}), подключаем поиск по описанию")
            for (query in queries.take(4)) {
                if (results.size >= 15) break
                val found = searchTgstat(query, limit = 50, peerType = "chat", searchByDescription = true)
                for (r in found) {
                    val key = r.username?.lowercase() ?: continue
                    if (seen.add(key)) results.add(r)
                }
            }
            log.info("TGStat после прохода с описанием: ${results.size}")
        }


        if (isDesignRelated(inputText) && results.none { it.link.contains("desgangchat") }) {
            results.add(FIXED_DESIGN_CHAT); seen.add("desgangchat")
        }
        if (isCreativeRelated(inputText) && results.none { it.link.contains("mskeventjob") }) {
            results.add(FIXED_EVENT_CHAT); seen.add("mskeventjob")
        }

        val candidates = results.take(15)
        log.info("TGStat до AI-фильтра: ${candidates.size} чатов")

        val filtered = if (candidates.isNotEmpty()) validateChannels(candidates, inputText) else candidates
        log.info("TGStat после AI-фильтра: ${filtered.size} (удалено ${candidates.size - filtered.size})")

        return ChatSearchResponse(results = filtered, queries = queries)
    }


    fun validateChannels(channels: List<ChatSearchResult>, query: String): List<ChatSearchResult> {
        if (groqApiKey.isBlank() || channels.isEmpty()) return channels
        val result = mutableListOf<ChatSearchResult>()
        channels.chunked(10).forEach { chunk ->
            val keep = validateChannelsBatch(chunk, query)
            chunk.forEachIndexed { idx, ch -> if (idx in keep) result.add(ch) }
        }
        return result
    }

    private fun validateChannelsBatch(channels: List<ChatSearchResult>, query: String): Set<Int> {
        if (groqApiKey.isBlank()) return channels.indices.toSet()

        val list = channels.mapIndexed { idx, ch ->
            val members = if (ch.participantsCount > 0) " | ${ch.participantsCount} участников" else ""
            val desc    = if (!ch.description.isNullOrBlank()) " | ${ch.description.take(100)}" else ""
            "${idx + 1}. «${ch.title}» (${ch.username})$members$desc"
        }.joinToString("\n")

        val prompt = """
Помогаю найти Telegram-группы для поиска лидов (заказчиков, клиентов).
Запрос пользователя: "$query"

$list

Для каждого: KEEP или REMOVE.
REMOVE только если: эротика/18+, казино/ставки, крипто-скам, тема кардинально не совпадает.
KEEP всё остальное — особенно профессиональные чаты, биржи заказов, сообщества фрилансеров.
Лучше лишний чат, чем пропустить нужный.

JSON без пояснений: {"keep": [1,2,3], "remove": [4]}
""".trimIndent()

        return try {
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON; setBearerAuth(groqApiKey)
            }
            val body = mapOf(
                "model"       to "llama-3.1-8b-instant",
                "messages"    to listOf(
                    mapOf("role" to "system", "content" to "Отвечай только JSON."),
                    mapOf("role" to "user",   "content" to prompt),
                ),
                "max_tokens"  to 200,
                "temperature" to 0.0,
            )
            val resp  = restTemplate.postForObject(
                "https://api.groq.com/openai/v1/chat/completions",
                HttpEntity(body, headers),
                GroqResponse::class.java,
            ) ?: return channels.indices.toSet()
            val raw   = resp.choices.firstOrNull()?.message?.content ?: return channels.indices.toSet()
            val clean = raw.replace(Regex("```json|```"), "").trim()
            val keepJson = Regex("\"keep\"\\s*:\\s*\\[([^]]*)]").find(clean)?.groupValues?.get(1)
                ?: return channels.indices.toSet()
            val keepIdx = Regex("\\d+").findAll(keepJson)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..channels.size }
                .map { it - 1 }
                .toSet()

            if (keepIdx.size < channels.size * 0.4 && channels.size >= 3) {
                log.warn("validateChannelsBatch: AI удалил слишком много (${keepIdx.size}/${channels.size}) — возвращаем всё")
                return channels.indices.toSet()
            }
            log.info("AI канал-фильтр: оставляем $keepIdx из ${channels.size}")
            keepIdx
        } catch (e: Exception) {
            log.warn("validateChannelsBatch ошибка: ${e.message}")
            channels.indices.toSet()
        }
    }


    private fun searchTgstat(
        query: String,
        limit: Int = 50,
        peerType: String = "chat",
        searchByDescription: Boolean = false,
    ): List<ChatSearchResult> {
        if (query.length < 2) {
            log.warn("TGStat: слишком короткий запрос '$query'")
            return emptyList()
        }

        val url = buildString {
            append("https://api.tgstat.ru/channels/search")
            append("?token=${java.net.URLEncoder.encode(tgstatApiKey, "UTF-8")}")
            append("&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            append("&country=${java.net.URLEncoder.encode(tgstatCountry, "UTF-8")}")
            append("&peer_type=$peerType")
            if (searchByDescription) append("&search_by_description=1")
            append("&limit=$limit")
        }

        log.info("TGStat запрос: q='$query' country=$tgstatCountry peer_type=$peerType limit=$limit desc=$searchByDescription")

        return try {
            val headers  = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
            val response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity<Void>(headers), TgstatApiResponse::class.java,
            )
            val body = response.body ?: run { log.warn("TGStat: пустое тело для '$query'"); return emptyList() }

            if (body.status != "ok") {
                log.error("TGStat: статус='${body.status}' error='${body.error}' для '$query'")
                return emptyList()
            }

            val items = body.response?.items ?: emptyList()
            log.info("TGStat '$query': ${items.size} сырых результатов")

            val mapped = items.mapNotNull { item ->


                val rawUsername = item.username?.trim()?.takeIf { it.isNotBlank() }
                    ?: run {
                        log.debug("TGStat: пропускаем '${item.title}' — нет username")
                        return@mapNotNull null
                    }

                val usernameSlug  = rawUsername.trimStart('@')
                val cleanUsername = "@$usernameSlug"


                if (usernameSlug.startsWith("+") || usernameSlug.lowercase().contains("joinchat")) {
                    log.debug("TGStat: пропускаем invite-username '${item.title}'")
                    return@mapNotNull null
                }
                val apiLink = item.link?.trim() ?: ""
                if (apiLink.contains("joinchat", ignoreCase = true) || apiLink.contains("/+")) {
                    log.debug("TGStat: пропускаем invite-link '${item.title}'")
                    return@mapNotNull null
                }


                val link = when {
                    apiLink.startsWith("https://") -> apiLink
                    apiLink.startsWith("http://")  -> apiLink
                    apiLink.isNotBlank()            -> "https://$apiLink"
                    else                            -> "https://t.me/$usernameSlug"
                }

                if (link.contains("joinchat", ignoreCase = true) ||
                    Regex("/\\+[A-Za-z0-9_-]+").containsMatchIn(link)
                ) {
                    log.debug("TGStat: invite в итоговой ссылке — пропускаем '${item.title}'")
                    return@mapNotNull null
                }


                val members = item.participantsCount ?: 0
                if (members in 1 until MIN_MEMBERS) {
                    log.debug("TGStat: мало участников '$usernameSlug': $members < $MIN_MEMBERS")
                    return@mapNotNull null
                }

                val resolvedPeerType = item.peerType?.lowercase()?.trim() ?: "chat"
                val tgstatLink = "https://tgstat.ru/${if (resolvedPeerType == "chat") "chat" else "channel"}/$usernameSlug"

                ChatSearchResult(
                    title             = item.title?.ifBlank { cleanUsername } ?: cleanUsername,
                    username          = cleanUsername,
                    description       = item.about?.trim()?.take(200)?.ifBlank { null },
                    participantsCount = members,
                    link              = link,
                    tgstatLink        = tgstatLink,
                    peerType          = resolvedPeerType,
                )
            }

            log.info("TGStat '$query': ${mapped.size} валидных после фильтров (из ${items.size})")
            mapped

        } catch (e: HttpClientErrorException) {
            log.error("TGStat HTTP ${e.statusCode} для '$query': ${e.responseBodyAsString}")
            emptyList()
        } catch (e: Exception) {
            log.error("TGStat исключение для '$query': ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }
}


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
    val language: String? = null,
)