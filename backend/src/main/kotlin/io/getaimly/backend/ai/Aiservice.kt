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

    // ─── Data classes ─────────────────────────────────────────────────────────

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
        val respondToServiceOffers: Boolean = false,
        val callback: (ValidationResult?) -> Unit,
    )



    fun validateAsync(
        messageText: String,
        keyword: String,
        contextMessages: List<String> = emptyList(),
        recentLeads: List<RecentLead> = emptyList(),
        businessContext: String? = null,
        respondToServiceOffers: Boolean = false,
        callback: (ValidationResult?) -> Unit,
    ) {
        if (apiKey.isBlank()) {
            log.debug("groq api key не задан — пропускаем ai валидацию")
            callback(null)
            return
        }
        val task = ValidationTask(
            messageText            = messageText,
            keyword                = keyword,
            contextMessages        = contextMessages,
            recentLeads            = recentLeads,
            businessContext        = businessContext,
            respondToServiceOffers = respondToServiceOffers,
            callback               = callback,
        )
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

    fun generateKeywords(businessContext: String): List<String> {
        if (apiKey.isBlank()) {
            log.warn("generateKeywords: groq api key не задан — генерация невозможна")
            throw IllegalStateException("AI-генерация недоступна: groq api key не задан")
        }

        val trimmed = businessContext.trim()
        if (trimmed.length < 20) {
            throw IllegalArgumentException("Описание бизнеса слишком короткое")
        }

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
                "- 13-20 ключевых фраз\n" +
                "- Только то, что реально пишут люди в чатах\n" +
                "- $cityInstruction\n" +
                "- В самый конец списка добавь 1-2 хештега на русском и 1-2 хештега на английском (формат: #слово, без пробелов). " +
                "Примеры: #дизайн, #smm, #маркетинг, #design, #marketing. Хештег — одно слово, без пробела.\n\n" +
                "Верни ТОЛЬКО JSON без пояснений и markdown:\n" +
                "{\"keywords\": [\"фраза 1\", \"фраза 2\", \"#хештег\"]}"

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

        log.info("generateKeywords: сгенерировано ${keywords.size} ключевых слов (${trimmed.take(60)}...)")
        return keywords
    }

    fun generateTgstatQueries(input: String): List<String> {
        if (apiKey.isBlank()) {
            log.warn("generateTgstatQueries: groq api key не задан")
            return fallbackQueries(input)
        }

        val trimmed = input.trim()

        val prompt = """
You are generating 5 search queries for TGStat — a Telegram channel/chat search engine.
Queries are matched against channel/chat TITLES and USERNAMES.

User topic: "$trimmed"

Generate EXACTLY 5 queries in this strict order:

Query 1: [main Russian term] + "вакансии"
  Examples: "дизайн вакансии", "smm вакансии", "разработчики вакансии", "маркетинг вакансии"

Query 2: [main English term] + "jobs"
  Examples: "design jobs", "smm jobs", "developers jobs", "marketing jobs"
  RULE: English term must be the DIRECT TRANSLATION of the Russian term in query 1.
  "дизайн" → "design jobs"
  "smm" → "smm jobs"
  "разработчики" → "developers jobs"
  "маркетинг" → "marketing jobs"
  "копирайтер" → "copywriter jobs"
  "таргет" → "targeting jobs" or "ads jobs"

Query 3: ONE single Russian word — the most recognizable topic word.
  Examples: "дизайн", "smm", "разработчики", "маркетинг", "копирайтеры"
  NEVER multiple words. NEVER "ux ui дизайн", NEVER "веб разработка". ONE word only.

Query 4: ONE single English word — DIRECT TRANSLATION of query 3.
  "дизайн" → "design"
  "smm" → "smm"
  "разработчики" → "developers"
  "программисты" → "developers"
  "маркетинг" → "marketing"
  "копирайтеры" → "copywriters"
  "таргетолог" → "targeting"
  "верстальщик" → "frontend"
  NEVER use "design" if the topic is programming/development/marketing.

Query 5: ONE related topic word (not the main one).
  For "дизайн" → "фриланс"
  For "разработчики"/"программисты" → "программисты" (or "it" or "фриланс")
  For "smm"/"маркетинг" → "реклама"

ALL queries must be lowercase.
Return ONLY JSON, no explanations:
{"queries": ["query1", "query2", "query3", "query4", "query5"]}
""".trimIndent()

        return try {
            val body = mapOf(
                "model" to EXPAND_MODEL,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "Return only JSON. No explanations. No markdown."),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "max_tokens" to 200,
                "temperature" to 0.0,
            )

            val response = restTemplate.postForObject(
                "https://api.groq.com/openai/v1/chat/completions",
                HttpEntity(body, HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    setBearerAuth(apiKey)
                }),
                GroqResponse::class.java,
            ) ?: return fallbackQueries(trimmed)

            val raw = response.choices.firstOrNull()?.message?.content
                ?: return fallbackQueries(trimmed)
            val clean = raw.replace(Regex("```json|```"), "").trim()

            val queriesJson = Regex("\"queries\"\\s*:\\s*\\[([^]]*)]")
                .find(clean)?.groupValues?.get(1)
                ?: return fallbackQueries(trimmed)

            val queries = Regex("\"([^\"]+)\"")
                .findAll(queriesJson)
                .map { it.groupValues[1].trim().lowercase() }
                .filter { it.length >= 2 }
                .distinct()
                .toList()

            log.info("generateTgstatQueries: запросы для \"${trimmed.take(40)}\": $queries")
            queries.ifEmpty { fallbackQueries(trimmed) }
        } catch (e: Exception) {
            log.warn("generateTgstatQueries ошибка: ${e.message}")
            fallbackQueries(trimmed)
        }
    }

    private fun fallbackQueries(input: String): List<String> {
        val term = input.trim().split(" ").first().take(20).lowercase()
        return listOf("$term вакансии", "$term jobs", term)
    }



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
            HttpEntity(body, headers),
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
                callGroq(
                    messageText            = task.messageText,
                    keyword                = task.keyword,
                    contextMessages        = task.contextMessages,
                    recentLeads            = task.recentLeads,
                    businessContext        = task.businessContext,
                    respondToServiceOffers = task.respondToServiceOffers,
                )
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
        respondToServiceOffers: Boolean = false,
    ): ValidationResult {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(apiKey)
        }

        val contextBlock = if (contextMessages.isNotEmpty()) {
            val ctx = contextMessages.joinToString("\n") { "    - \"$it\"" }
            "\n\nКонтекст разговора (предыдущие сообщения в чате):\n$ctx"
        } else ""

        val leadsBlock = if (recentLeads.isNotEmpty()) {
            val leads = recentLeads.take(5).joinToString("\n") { l ->
                "    - @${l.authorUsername}: \"${l.messageText}\" (${l.foundAt})"
            }
            "\n\nЛиды, уже найденные для этого пользователя за последние 7 дней (справочно):\n$leads\n\n" +
                    "ВАЖНО: не отклоняй лид только потому что автор уже встречался.\n" +
                    "Дубль только если: тот же автор + та же услуга + практически то же сообщение."
        } else ""

        val businessBlock = if (!businessContext.isNullOrBlank()) {
            "\n\nИнформация о бизнесе владельца системы (ищем клиентов именно для этого бизнеса):\n$businessContext"
        } else ""


        val serviceOffersRule = if (respondToServiceOffers) {
            """
РЕЖИМ «ПРЕДЛОЖЕНИЯ УСЛУГ»: ВКЛЮЧЁН.
В этом режиме valid=true также для сообщений, где автор ПРЕДЛАГАЕТ свои услуги и ищет клиентов/заказы.
Такой автор является потенциальным партнёром или лидом для владельца.

"""
        } else {
            """
РЕЖИМ ПОИСКА ЛИДОВ (стандартный).
Наш сервис ищет ЛИДОВ — людей, которые НУЖДАЮТСЯ в услуге и готовы платить.

ЖЁСТКОЕ ПРАВИЛО — верни valid=false НЕМЕДЛЕННО если хотя бы один из признаков:

ПРИЗНАКИ ОФФЕРА (автор предлагает, а не ищет):
  • Автор представляет себя как специалиста: "я дизайнер", "я разработчик", "я аниматор", "я актёр"
  • Автор предлагает выполнить работу: "выполню", "сделаю", "возьмусь", "помогу с", "занимаюсь"
  • Автор ищет заказы/клиентов: "ищу заказы", "ищу клиентов", "ищу проекты", "открыт к предложениям"
  • Автор перечисляет своё портфолио или опыт
  • Формат резюме или самопрезентации

ПРИЗНАКИ МАССОВОГО ОБЪЯВЛЕНИЯ (не персональный запрос на услугу):
  • Кастинг, кастинги — массовый набор людей (актёров, моделей, аниматоров)
  • HR и найм: "требуются сотрудники", "открыта вакансия", "приглашаем на работу"
  • Массовый набор на курсы, тренинги, стажировки
  • Реклама мероприятий, семинаров (автор организует, а не ищет подрядчика)
  • Объявление адресовано неограниченному кругу лиц, а не является личным запросом

ПРИЗНАКИ РЕКЛАМЫ И СПАМА:
  • Продвижение своих услуг, продуктов, каналов
  • Партнёрские программы, реферальные ссылки
  • "Подписывайтесь", "переходите", "смотрите"

Поясняй в поле reason: что именно указывает на оффер/объявление/рекламу.
Пример reason для оффера: "Автор представляет себя аниматором и предлагает услуги, а не ищет их"
Пример reason для кастинга: "Массовый кастинг на сериалы — объявление о наборе, не персональный запрос"
Пример reason для лида: "Автор ищет аниматора для детского праздника, указал дату и количество детей"

"""
        }

        val prompt = """
${serviceOffersRule}Ты — система фильтрации лидов для Telegram-мониторинга.
Твоя задача: определить, является ли сообщение реальным ЛИЧНЫМ запросом на покупку/поиск услуги.

Ключевое слово, по которому найдено сообщение: "$keyword"
$contextBlock
$leadsBlock
$businessBlock

Целевое сообщение:
"$messageText"

Анализируй последовательно:
1. Кто автор — покупатель/заказчик или специалист/продавец/организатор?
2. Это персональный запрос или массовое объявление?
3. Есть ли конкретная потребность (задача, бюджет, сроки, детали)?
4. Это живой диалог или рекламная рассылка?
5. Если задан бизнес-контекст владельца — подходит ли этот лид?

valid=true ТОЛЬКО если:
- Конкретный человек ИЩЕТ услугу или исполнителя для себя
- Есть реальная потребность (задача, событие, проект)
- Сообщение инициирует диалог, а не является рекламой

Ответь строго JSON (без текста вне JSON):
{"valid": true/false, "reason": "одно конкретное предложение с объяснением решения", "confidence": "low/medium/high"}
""".trimIndent()

        val body = mapOf(
            "model" to MAIN_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "Ты фильтруешь лиды. Отвечай только JSON. Никакого текста вне JSON."),
                mapOf("role" to "user", "content" to prompt),
            ),
            "max_tokens" to 200,
            "temperature" to 0.0,
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