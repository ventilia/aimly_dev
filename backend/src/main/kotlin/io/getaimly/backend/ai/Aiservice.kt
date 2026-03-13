package io.getaimly.backend.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class AiService(
    @Value("\${groq.api-key:}") private val apiKey: String,
) {
    private val log = LoggerFactory.getLogger(AiService::class.java)
    private val restTemplate = RestTemplate()

    private val MAIN_MODEL   = "llama-3.1-8b-instant"
    private val EXPAND_MODEL = "llama-3.3-70b-versatile"

    private val queue = ArrayBlockingQueue<ValidationTask>(50)
    private val requestsThisMinute = AtomicInteger(0)
    private var minuteStart = Instant.now()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-worker").also { it.isDaemon = true }
    }

    init {
        executor.submit { processQueue() }
    }

    data class ValidationResult(
        val valid: Boolean,
        val reason: String,
        val confidence: String = "medium",
    )

    data class RecentLead(
        val authorUsername: String,
        val messageText: String,
        val foundAt: String,
    )

    data class ValidationTask(
        val messageText: String,
        val keyword: String,
        val contextMessages: List<String>,
        val recentLeads: List<RecentLead> = emptyList(),
        val businessContext: String? = null,
        val callback: (ValidationResult?) -> Unit,
    )

    fun validateAsync(
        messageText: String,
        keyword: String,
        contextMessages: List<String> = emptyList(),
        recentLeads: List<RecentLead> = emptyList(),
        businessContext: String? = null,
        callback: (ValidationResult?) -> Unit,
    ) {
        if (apiKey.isBlank()) {
            log.debug("groq api key не задан — пропускаем ai валидацию")
            callback(null)
            return
        }
        val task = ValidationTask(messageText, keyword, contextMessages, recentLeads, businessContext, callback)
        val offered = queue.offer(task)
        if (!offered) {
            log.warn("очередь ai переполнена — пропускаем лид")
            callback(null)
        }
    }


    fun filterRelevantContext(messageText: String, rawContext: List<String>): List<String> {
        if (apiKey.isBlank() || rawContext.isEmpty()) return rawContext

        return try {
            val contextList = rawContext.mapIndexed { i, msg -> "${i + 1}. \"$msg\"" }.joinToString("\n")
            val prompt = """
Целевое сообщение:
"$messageText"

Предшествующие сообщения в чате:
$contextList

Задача: определи, какие из предшествующих сообщений являются ПРЯМЫМ контекстом для целевого сообщения — то есть части одного диалога или обсуждения.

Верни ТОЛЬКО номера сообщений, которые являются контекстом. Если ни одно не является контекстом — верни пустой список.

Ответь строго JSON без пояснений: {"relevant": [1, 2]} или {"relevant": []}
""".trimIndent()

            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(apiKey)
            }

            val body = mapOf(
                "model" to MAIN_MODEL,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "Отвечай только JSON. Никаких пояснений."),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "max_tokens" to 80,
                "temperature" to 0.0,
            )

            val response = restTemplate.postForObject(
                "https://api.groq.com/openai/v1/chat/completions",
                HttpEntity(body, headers),
                GroqResponse::class.java,
            ) ?: return rawContext

            val raw = response.choices.firstOrNull()?.message?.content ?: return rawContext
            val clean = raw.replace(Regex("```json|```"), "").trim()

            val relevantJson = Regex("\"relevant\"\\s*:\\s*\\[([^]]*)]")
                .find(clean)?.groupValues?.get(1) ?: return emptyList()

            val indices = Regex("\\d+")
                .findAll(relevantJson)
                .map { it.value.toIntOrNull() }
                .filterNotNull()
                .filter { it in 1..rawContext.size }
                .toList()

            indices.map { rawContext[it - 1] }
        } catch (e: Exception) {
            log.warn("filterRelevantContext ошибка: ${e.message}")
            rawContext
        }
    }


    fun expandKeyword(keyword: String): List<String> {
        if (apiKey.isBlank()) {
            log.info("keyword expand: groq api key не задан, варианты не генерируются для \"$keyword\"")
            return listOf(keyword)
        }

        return try {
            val variants = callGroqExpand(keyword)

            val result = (listOf(keyword) + variants)
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()

            log.info(
                "keyword expand: \"$keyword\" → [${result.joinToString(", ") { "\"$it\"" }}]" +
                        " (итого ${result.size}: оригинал + ${result.size - 1} сгенерировано)"
            )

            result
        } catch (e: Exception) {
            log.warn("keyword expand: ошибка для \"$keyword\" — ${e.message}. Используем только оригинал.")
            listOf(keyword)
        }
    }


    /**
     * Генерация ключевых слов на основе описания бизнеса.
     *
     * ВАЖНО: город включается в ключевые слова ТОЛЬКО если он явно упомянут
     * в businessContext. Модель не придумывает географию самостоятельно.
     */
    fun generateKeywords(businessContext: String): List<String> {
        if (apiKey.isBlank()) {
            log.warn("generateKeywords: groq api key не задан — генерация невозможна")
            throw IllegalStateException("AI-генерация недоступна: groq api key не задан")
        }

        val trimmed = businessContext.trim()
        if (trimmed.length < 20) {
            throw IllegalArgumentException("Описание бизнеса слишком короткое")
        }

        // Извлекаем город из описания бизнеса, если он там есть
        val cityHint = extractCityFromContext(trimmed)
        val cityInstruction = if (cityHint != null) {
            "- В описании бизнеса упомянут город: \"$cityHint\". " +
                    "Добавь несколько фраз с этим городом (например: \"ищу дизайнера $cityHint\", \"нужен разработчик в $cityHint\")."
        } else {
            "- Город или регион в описании НЕ упомянут — НЕ добавляй никаких городов или географических уточнений в ключевые слова. " +
                    "Генерируй универсальные фразы без географической привязки."
        }

        val prompt = "Ты — AI-помощник для генерации ключевых слов для мониторинга Telegram-чатов.\n\n" +
                "Описание бизнеса пользователя:\n" +
                trimmed + "\n\n" +
                "Сгенерируй ключевые слова и фразы, которые потенциальные клиенты пишут в Telegram-чатах, " +
                "когда ищут услуги/продукты этого бизнеса.\n\n" +
                "Требования:\n" +
                "- Фразы должны быть реальными запросами от клиентов (не рекламные слоганы)\n" +
                "- Включи разные варианты формулировок одного запроса\n" +
                "- Включи фразы-сигналы намерения: \"ищу\", \"нужен\", \"требуется\", \"посоветуйте\", \"порекомендуйте\"\n" +
                "- 15-25 ключевых фраз\n" +
                "- Только то, что реально пишут люди в чатах\n" +
                "- $cityInstruction\n\n" +
                "Верни ТОЛЬКО JSON без пояснений и markdown:\n" +
                "{\"keywords\": [\"фраза 1\", \"фраза 2\", \"фраза 3\"]}"

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(apiKey)
        }

        val body = mapOf(
            "model" to EXPAND_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "Отвечай только JSON. Никаких пояснений, никаких markdown-блоков."),
                mapOf("role" to "user", "content" to prompt),
            ),
            "max_tokens" to 1000,
            "temperature" to 0.4,
        )

        val response = restTemplate.postForObject(
            "https://api.groq.com/openai/v1/chat/completions",
            HttpEntity(body, headers),
            GroqResponse::class.java,
        ) ?: throw RuntimeException("пустой ответ от groq при генерации ключевых слов")

        val raw = response.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("нет content в ответе groq")

        val clean = raw.replace(Regex("```json|```"), "").trim()

        val keywordsJson = Regex("\"keywords\"\\s*:\\s*\\[([^]]*)]")
            .find(clean)
            ?.groupValues?.get(1)
            ?: run {
                log.warn("generateKeywords: не удалось распарсить ответ: $clean")
                throw RuntimeException("Некорректный формат ответа от AI")
            }

        val keywords = Regex("\"([^\"]+)\"")
            .findAll(keywordsJson)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()

        log.info("generateKeywords: сгенерировано ${keywords.size} ключевых слов для бизнеса (${trimmed.take(60)}...)")
        return keywords
    }

    /**
     * Извлекает город из текста бизнес-контекста.
     * Возвращает null, если город не упомянут.
     */
    private fun extractCityFromContext(context: String): String? {
        if (apiKey.isBlank()) return null

        return try {
            val prompt = """
Определи, упоминается ли в тексте конкретный город, регион или страна как место работы/предоставления услуг.

Текст:
"$context"

Если город/регион упомянут — верни его название. Если нет — верни пустую строку.

Ответь строго JSON: {"city": "Киев"} или {"city": ""}
""".trimIndent()

            val body = mapOf(
                "model" to MAIN_MODEL,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "Отвечай только JSON. Никаких пояснений."),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "max_tokens" to 30,
                "temperature" to 0.0,
            )

            val response = restTemplate.postForObject(
                "https://api.groq.com/openai/v1/chat/completions",
                HttpEntity(body, HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    setBearerAuth(apiKey)
                }),
                GroqResponse::class.java,
            ) ?: return null

            val raw = response.choices.firstOrNull()?.message?.content ?: return null
            val clean = raw.replace(Regex("```json|```"), "").trim()
            val city = Regex("\"city\"\\s*:\\s*\"([^\"]*)\"").find(clean)?.groupValues?.get(1)?.trim()
            if (city.isNullOrBlank()) null else city
        } catch (e: Exception) {
            log.warn("extractCityFromContext ошибка: ${e.message}")
            null
        }
    }

    /**
     * Генерирует поисковые запросы для TGStat на основе описания бизнеса или произвольного текста.
     * Возвращает список из 2-4 коротких запросов для поиска подходящих Telegram-чатов.
     */
    fun generateTgstatQueries(input: String): List<String> {
        if (apiKey.isBlank()) {
            log.warn("generateTgstatQueries: groq api key не задан")
            return listOf(input.trim().take(50))
        }

        val trimmed = input.trim()

        val prompt = """
Ты — AI-помощник. Пользователь ищет Telegram-чаты, где обитает его целевая аудитория.

Описание пользователя:
"$trimmed"

Задача: сгенерируй 3-4 коротких поисковых запроса для поиска релевантных Telegram-чатов и групп.
Запросы должны быть такими, как их вводят в поисковике:
- Короткие (1-4 слова)
- На русском языке (и 1-2 на английском если тематика международная)
- Описывают тематику чата, а не услугу пользователя
- Примеры: "дизайн интерьера", "ремонт квартир", "freelance developers", "smm маркетинг"

Верни ТОЛЬКО JSON без пояснений:
{"queries": ["запрос 1", "запрос 2", "запрос 3"]}
""".trimIndent()

        return try {
            val body = mapOf(
                "model" to MAIN_MODEL,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "Отвечай только JSON. Никаких пояснений, никаких markdown-блоков."),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "max_tokens" to 200,
                "temperature" to 0.3,
            )

            val response = restTemplate.postForObject(
                "https://api.groq.com/openai/v1/chat/completions",
                HttpEntity(body, HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    setBearerAuth(apiKey)
                }),
                GroqResponse::class.java,
            ) ?: return listOf(trimmed.take(50))

            val raw = response.choices.firstOrNull()?.message?.content ?: return listOf(trimmed.take(50))
            val clean = raw.replace(Regex("```json|```"), "").trim()

            val queriesJson = Regex("\"queries\"\\s*:\\s*\\[([^]]*)]")
                .find(clean)?.groupValues?.get(1) ?: return listOf(trimmed.take(50))

            val queries = Regex("\"([^\"]+)\"")
                .findAll(queriesJson)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()

            log.info("generateTgstatQueries: сгенерировано ${queries.size} запросов для \"${trimmed.take(40)}\"")
            queries.ifEmpty { listOf(trimmed.take(50)) }
        } catch (e: Exception) {
            log.warn("generateTgstatQueries ошибка: ${e.message}")
            listOf(trimmed.take(50))
        }
    }


    private fun callGroqExpand(keyword: String): List<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(apiKey)
        }

        val prompt = """
Ты — система генерации вариантов ключевых слов для поиска лидов в Telegram-чатах русскоязычной IT/фриланс аудитории.

Ключевое слово: "$keyword"

Задача: сгенерируй все реальные варианты того, как люди могут написать эту же потребность.

Генерируй варианты по четырём направлениям:

1. МОРФОЛОГИЯ — разные формы глагола и рода:
   ищу / ищем / ищут / нужен / нужна / нужны / нужно / требуется / требуются /
   хочу найти / хотим найти / мне нужен / нам нужен / посоветуйте / порекомендуйте

2. СМЕШАННЫЙ ЯЗЫК (рунглиш) — русские слова + английский термин:
   если в ключевом слове есть русский термин (дизайнер, разработчик, верстальщик и т.д.),
   замени его на английский эквивалент или аббревиатуру в тех же фразах.
   Примеры: "ищу дизайнера" → "ищу designer", "нужен frontend разработчик" → "нужен frontend dev"

3. ТОЛЬКО АНГЛИЙСКИЙ — полностью английские варианты запроса:
   "looking for designer", "need frontend developer", "hiring smm manager" и т.д.
   (только если термин имеет устоявшийся английский эквивалент)

4. СОКРАЩЕНИЯ и РАЗГОВОРНЫЙ СТИЛЬ Telegram:
   dev вместо developer, фронт вместо frontend, дизайн вместо дизайнер, smm без расшифровки

Правила:
- Генерируй только варианты где автор ИЩЕТ (не предлагает) услугу
- НЕ включай варианты "предлагаю", "выполню", "доступен" — только запросы
- НЕ включай оригинал — он уже есть
- Максимум 12 вариантов, приоритет — наиболее реальные фразы из Telegram
- Только реальные фразы, которые люди реально пишут

Ответь строго JSON без пояснений и markdown:
{"variants": ["вариант 1", "вариант 2"]}
""".trimIndent()

        val body = mapOf(
            "model" to EXPAND_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "Отвечай только JSON. Никаких пояснений, никаких markdown-блоков."),
                mapOf("role" to "user", "content" to prompt),
            ),
            "max_tokens" to 500,
            "temperature" to 0.3,
        )

        val response = restTemplate.postForObject(
            "https://api.groq.com/openai/v1/chat/completions",
            HttpEntity(body, HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(apiKey)
            }),
            GroqResponse::class.java,
        ) ?: throw RuntimeException("пустой ответ от groq expand")

        val raw = response.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("нет content в ответе groq expand")

        val clean = raw.replace(Regex("```json|```"), "").trim()

        val variantsJson = Regex("\"variants\"\\s*:\\s*\\[([^]]*)]")
            .find(clean)
            ?.groupValues?.get(1)
            ?: run {
                log.warn("keyword expand: не смогли распарсить ответ: $clean")
                return emptyList()
            }

        return Regex("\"([^\"]+)\"")
            .findAll(variantsJson)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
    }


    private fun processQueue() {
        while (true) {
            val task = queue.poll(5, TimeUnit.SECONDS) ?: continue
            throttle()
            val result = runCatching {
                callGroq(task.messageText, task.keyword, task.contextMessages, task.recentLeads, task.businessContext)
            }
                .onFailure { log.warn("groq ошибка: ${it.message}") }
                .getOrNull()
            task.callback(result)
        }
    }


    private fun throttle() {
        val now = Instant.now()
        if (now.epochSecond - minuteStart.epochSecond >= 60) {
            requestsThisMinute.set(0)
            minuteStart = now
        }

        val count = requestsThisMinute.incrementAndGet()
        if (count > 25) {
            val waitMs = 60_000 - (now.epochSecond - minuteStart.epochSecond) * 1000
            if (waitMs > 0) {
                log.debug("rate limit: ждём ${waitMs}ms")
                Thread.sleep(waitMs + 500)
            }
            requestsThisMinute.set(1)
            minuteStart = Instant.now()
        }
    }

    private fun callGroq(
        messageText: String,
        keyword: String,
        contextMessages: List<String>,
        recentLeads: List<RecentLead> = emptyList(),
        businessContext: String? = null,
    ): ValidationResult {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(apiKey)
        }

        val contextBlock = if (contextMessages.isNotEmpty()) {
            val ctx = contextMessages.joinToString("\n") { "    - \"$it\"" }
            """
            |
            |контекст разговора (сообщения перед этим):
            |$ctx
            """.trimMargin()
        } else ""

        val leadsBlock = if (recentLeads.isNotEmpty()) {
            val leads = recentLeads.take(5).joinToString("\n") { l ->
                "    - @${l.authorUsername}: \"${l.messageText}\" (${l.foundAt})"
            }
            """
            |
            |лиды уже найденные для этого пользователя за последние 7 дней (справочно):
            |$leads
            |
            |ВАЖНО: не отклоняй лид только потому что автор уже встречался.
            |один человек может искать разные услуги или писать в разных чатах — это нормально.
            |дубль только если: тот же автор + та же услуга + практически то же сообщение.
            """.trimMargin()
        } else ""

        val businessBlock = if (!businessContext.isNullOrBlank()) {
            """
            |
            |информация о бизнесе владельца системы (используй для точной фильтрации — ищем клиентов именно для этого бизнеса):
            |$businessContext
            """.trimMargin()
        } else ""

        val prompt = """
ты — умный ии-ассистент для поиска лидов в telegram-чатах.
твоя задача: определить, является ли сообщение реальным запросом на покупку услуги или поиском исполнителя.

ключевое слово, по которому найдено сообщение: "$keyword"
$contextBlock
$leadsBlock
$businessBlock

целевое сообщение:
"$messageText"

анализируй:
1. намерение автора — он ищет исполнителя/услугу или нет?
2. контекст разговора — это продолжение другой темы или новый запрос?
3. тональность — реальный запрос или просто упоминание слова, шутка, спам?
4. срочность и готовность к сделке — есть ли признаки готовности платить?
5. автор не предлагает услуги сам (не исполнитель ищет работу, а заказчик ищет исполнителя)
6. если задан бизнес-контекст владельца — насколько этот лид подходит именно для его бизнеса?

valid=true если:
- автор явно ищет услугу, исполнителя, подрядчика
- готов платить или обсуждает бюджет
- есть реальная потребность (не просто вопрос "а сколько стоит")
- это не ответ на чужой запрос

valid=false если:
- спам, реклама, оффтоп, шутка
- автор сам предлагает услуги (исполнитель ищет клиентов) — ЕСЛИ тумблер "реагировать на предложения услуг" НЕ включён
- ключевое слово упомянуто случайно или в другом контексте
- это ответ на чужой запрос (не инициирует диалог)
- слишком общее упоминание без намерения купить
- буквальный дубль: тот же автор + та же услуга + практически то же сообщение

ответь строго в формате json (без пояснений вне json):
{"valid": true/false, "reason": "краткое объяснение в 1-2 предложения", "confidence": "low/medium/high"}
""".trimIndent()

        val body = mapOf(
            "model" to MAIN_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "ты помогаешь фильтровать лиды. отвечай только json."),
                mapOf("role" to "user", "content" to prompt)
            ),
            "max_tokens" to 250,
            "temperature" to 0.1,
        )

        val response = restTemplate.postForObject(
            "https://api.groq.com/openai/v1/chat/completions",
            HttpEntity(body, headers),
            GroqResponse::class.java,
        ) ?: throw RuntimeException("пустой ответ от groq")

        val content = response.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("нет content в ответе groq")

        return parseResult(content)
    }

    private fun parseResult(content: String): ValidationResult {
        val jsonStr = content.substringAfter("{").substringBefore("}").let { "{$it}" }
        val valid = jsonStr.contains("\"valid\":true") || jsonStr.contains("\"valid\": true")
        val reason = Regex("\"reason\":\\s*\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1)
            ?: content.take(200)
        val confidence = Regex("\"confidence\":\\s*\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1)
            ?: "medium"
        return ValidationResult(valid = valid, reason = reason, confidence = confidence)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroqResponse(
    val choices: List<GroqChoice> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroqChoice(
    val message: GroqMessage = GroqMessage(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroqMessage(
    val content: String = "",
)