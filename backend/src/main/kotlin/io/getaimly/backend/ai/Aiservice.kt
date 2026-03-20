package io.getaimly.backend.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class AiService(
    @Value("\${groq.api-key:}") private val apiKeyRaw: String,
) {
    private val log = LoggerFactory.getLogger(AiService::class.java)
    private val restTemplate = RestTemplate()

    private val MAIN_MODEL   = "llama-3.1-8b-instant"
    private val EXPAND_MODEL = "llama-3.3-70b-versatile"
    private val GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions"

    // Разбиваем строку ключей по запятой, убираем пробелы и пустые элементы
    private val apiKeys: List<String> = apiKeyRaw
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    // Первый ключ — для проверок isBlank во всех местах
    private val apiKey: String get() = apiKeys.firstOrNull() ?: ""

    private val queue = ArrayBlockingQueue<ValidationTask>(50)
    private val requestsThisMinute = AtomicInteger(0)
    private var minuteStart = Instant.now()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-worker").also { it.isDaemon = true }
    }

    init {
        executor.submit { processQueue() }
        if (apiKeys.isNotEmpty()) {
            log.info("AiService инициализирован: ${apiKeys.size} Groq API ключ(а/ей)")
        }
    }

    // ─── Data classes ────────────────────────────────────────────────────────────

    data class ValidationResult(
        val valid:      Boolean,
        val reason:     String,
        val confidence: String = "medium",
    )

    data class RecentLead(
        val authorUsername: String,
        val messageText:    String,
        val foundAt:        String,
    )

    data class ValidationTask(
        val messageText:            String,
        val keyword:                String,
        val contextMessages:        List<String>,
        val recentLeads:            List<RecentLead> = emptyList(),
        val businessContext:        String?          = null,
        val respondToServiceOffers: Boolean          = false,
        val callback:               (ValidationResult?) -> Unit,
    )

    // ─── Публичные методы ────────────────────────────────────────────────────────

    fun validateAsync(
        messageText:            String,
        keyword:                String,
        contextMessages:        List<String>     = emptyList(),
        recentLeads:            List<RecentLead> = emptyList(),
        businessContext:        String?          = null,
        respondToServiceOffers: Boolean          = false,
        callback:               (ValidationResult?) -> Unit,
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
        if (!queue.offer(task)) {
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

            val body = mapOf(
                "model" to MAIN_MODEL,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "Отвечай только JSON. Никаких пояснений."),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "max_tokens" to 80,
                "temperature" to 0.0,
            )

            val raw = postToGroqWithFallback(body)?.choices?.firstOrNull()?.message?.content
                ?: return rawContext
            val clean = raw.replace(Regex("```json|```"), "").trim()

            val relevantJson = Regex("\"relevant\"\\s*:\\s*\\[([^]]*)]")
                .find(clean)?.groupValues?.get(1) ?: return emptyList()

            Regex("\\d+")
                .findAll(relevantJson)
                .map { it.value.toIntOrNull() }
                .filterNotNull()
                .filter { it in 1..rawContext.size }
                .map { rawContext[it - 1] }
                .toList()
        } catch (e: Exception) {
            log.warn("filterRelevantContext ошибка: ${e.message}")
            rawContext
        }
    }

    fun expandKeyword(keyword: String): List<String> {
        if (apiKey.isBlank()) {
            log.info("keyword expand: groq api key не задан для \"$keyword\"")
            return listOf(keyword)
        }

        return try {
            val variants = callGroqExpand(keyword)
            val result = (listOf(keyword) + variants)
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            log.info("keyword expand: \"$keyword\" → ${result.size} вариантов")
            result
        } catch (e: Exception) {
            log.warn("keyword expand: ошибка для \"$keyword\" — ${e.message}")
            listOf(keyword)
        }
    }

    fun generateKeywords(businessContext: String): List<String> {
        if (apiKey.isBlank()) throw IllegalStateException("AI-генерация недоступна: groq api key не задан")

        val trimmed = businessContext.trim()
        if (trimmed.length < 20) throw IllegalArgumentException("Описание бизнеса слишком короткое")

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

        val body = mapOf(
            "model" to EXPAND_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "Отвечай только JSON. Никаких пояснений, никаких markdown-блоков."),
                mapOf("role" to "user", "content" to prompt),
            ),
            "max_tokens" to 1000,
            "temperature" to 0.4,
        )

        val raw = postToGroqWithFallback(body)?.choices?.firstOrNull()?.message?.content
            ?: throw RuntimeException("пустой ответ от groq при генерации ключевых слов")

        val clean = raw.replace(Regex("```json|```"), "").trim()

        val keywordsJson = Regex("\"keywords\"\\s*:\\s*\\[([^]]*)]")
            .find(clean)?.groupValues?.get(1)
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

    fun generateTgstatQueries(input: String, peerType: String? = null): List<String> {
        if (apiKey.isBlank()) {
            log.warn("generateTgstatQueries: groq api key не задан")
            return fallbackQueries(input, peerType)
        }

        val trimmed = input.trim()

        val targetDescription = when (peerType) {
            "channel" -> "Telegram CHANNELS (каналы). Каналы — это одностороннее вещание, там нет диалога."
            "chat"    -> "Telegram GROUPS/CHATS (группы, чаты). Группы — это место, где люди общаются, задают вопросы, ищут исполнителей."
            else      -> "Telegram GROUPS и CHANNELS (группы и каналы)."
        }

        val namingPatterns = when (peerType) {
            "channel" ->
                """
NAMING PATTERNS for Telegram CHANNELS:
- Channels use topic words directly: "smm", "дизайн", "маркетинг", "разработка"
- News/blog style: "smm news", "design blog", "it daily"
- Professional: "маркетологи" (plural profession noun used as channel name)
- Digest/aggregator style: "ux digest", "frontend digest"
- Do NOT use "чат" or "chat" — those are group names, not channels
"""
            else ->
                """
NAMING PATTERNS for Telegram GROUPS:
- Groups use PLURAL profession nouns: "дизайнеры", "разработчики", "маркетологи", "фотографы"
- Community words: "дизайн чат", "smm чат", "design chat", "it тусовка"  
- Freelance/orders: "дизайн фриланс", "design freelance", "дизайн заказы"
- Simple topic word: "дизайн", "smm", "it"
- Do NOT use "вакансии" or "jobs" — those match broadcast channels
"""
        }

        val prompt = """
You are generating search queries for TGStat API to find $targetDescription

User topic: "$trimmed"

STEP 1 — UNDERSTAND THE TOPIC:
If the topic contains multiple words or is abstract (e.g. "креатив, клиенты, вакансии" or "smm маркетинг продвижение"):
- Identify the MAIN professional niche, not just the first word
- "креатив, клиенты, вакансии" → the person is likely in creative/marketing field looking for clients
- "smm, таргет, реклама" → SMM/digital marketing niche
- Focus ALL 8 queries on that ONE niche — do not mix unrelated topics

$namingPatterns

Generate EXACTLY 8 search queries. Each query is a short phrase (1-3 words) that would appear in the name or description of relevant Telegram ${if (peerType == "channel") "channels" else "groups"}.

CRITICAL RULES:
- Queries must be SHORT (1-3 words) — TGStat searches by name/title
- Use BOTH Russian and English variations — many groups have English names
- Cover different naming conventions people actually use
- Do NOT generate sentences or full phrases like "ищу дизайнера" — those are keywords, not group names
- Generate queries that would match the actual NAME of a channel/group, not messages inside it
- All 8 queries MUST be about the same niche — no mixing topics

q1: Russian profession in PLURAL form (one word only for groups) OR core topic (for channels).
  Groups: "дизайнеры" "разработчики" "маркетологи" "фотографы"
  Channels: "дизайн" "маркетинг" "smm" "разработка"

q2: English profession in PLURAL (groups) OR English topic word (channels). 
  Groups: "designers" "developers" "marketers" "photographers"
  Channels: "design" "marketing" "smm" "development"

q3: Russian topic + "фриланс" OR topic + "заказы" — specific compound queries.
  Groups: "разработка фриланс" "дизайн фриланс" "smm фриланс" "программирование фриланс"
  Channels: "дизайн канал" "разработка канал"
  CRITICAL: NEVER use "ит чат", "it чат" — too short/generic, matches unrelated chats.

q4: Russian topic + "заказы" OR topic + "биржа".
  Groups: "разработка заказы" "дизайн заказы" "smm заказы" "программирование заказы"
  Channels: "разработка заказы" "дизайн заказы"

q5: English SPECIFIC multi-word profession (NEVER single "it", "dev", "chat").
  Groups: "web developers" "software developers" "frontend developers" "smm specialists"
  Channels: "web development" "software development" "digital marketing"
  NEVER: "it chat", "dev chat", "it freelance" — these are too generic.

q6: English topic + "freelance" (specific, not generic).
  Groups: "design freelance" "smm freelance" "development freelance"
  Channels: "design channel" "smm channel"

q7: Russian topic + "сообщество" OR topic + "профи".
  Groups: "разработчики профи" "дизайнеры профи" "smm сообщество"
  Channels: "разработчики" "маркетологи" "дизайнеры"

q8: Single Russian core topic word (MUST be specific, 4+ chars).
  "дизайн" "smm" "маркетинг" "фотография" "разработка" "программирование" "автоматизация"
  NEVER: "ит", "it" — too short and generic.

All queries must be lowercase.
Return ONLY JSON, no extra text:
{"queries": ["q1","q2","q3","q4","q5","q6","q7","q8"]}
""".trimIndent()

        return try {
            val body = mapOf(
                "model" to EXPAND_MODEL,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "Return only JSON. No explanations. No markdown."),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "max_tokens" to 300,
                "temperature" to 0.0,
            )

            val raw = postToGroqWithFallback(body)?.choices?.firstOrNull()?.message?.content
                ?: return fallbackQueries(trimmed, peerType)
            val clean = raw.replace(Regex("```json|```"), "").trim()

            val queriesJson = Regex("\"queries\"\\s*:\\s*\\[([^]]*)]")
                .find(clean)?.groupValues?.get(1)
                ?: return fallbackQueries(trimmed, peerType)

            val queries = Regex("\"([^\"]+)\"")
                .findAll(queriesJson)
                .map { it.groupValues[1].trim().lowercase() }
                .filter { it.length >= 2 }
                .distinct()
                .toList()

            log.info("generateTgstatQueries: запросы для \"${trimmed.take(40)}\": $queries")
            queries.ifEmpty { fallbackQueries(trimmed, peerType) }
        } catch (e: Exception) {
            log.warn("generateTgstatQueries ошибка: ${e.message}")
            fallbackQueries(trimmed, peerType)
        }
    }

    // ─── Вспомогательные методы ──────────────────────────────────────────────────

    private fun fallbackQueries(input: String, peerType: String? = null): List<String> {
        val term = input.trim().split(" ").first().take(20).lowercase()
        return if (peerType == "channel") {
            listOf(term, "${term}s", "$term blog", "$term news", "$term канал", "${term}ы")
        } else {
            listOf("${term}ы", "${term}ers", "$term чат", "$term freelance", "$term заказы", term)
        }
    }

    private fun extractCityFromContext(context: String): String? {
        if (apiKey.isBlank()) return null
        return try {
            val prompt = """
Определи, упоминается ли в тексте конкретный город, регион или страна как место работы/предоставления услуг.
Текст: "$context"
Если упомянут — верни название. Если нет — верни пустую строку.
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
            val raw = postToGroqWithFallback(body)?.choices?.firstOrNull()?.message?.content ?: return null
            val clean = raw.replace(Regex("```json|```"), "").trim()
            val city = Regex("\"city\"\\s*:\\s*\"([^\"]*)\"").find(clean)?.groupValues?.get(1)?.trim()
            if (city.isNullOrBlank()) null else city
        } catch (e: Exception) {
            log.warn("extractCityFromContext ошибка: ${e.message}")
            null
        }
    }

    private fun callGroqExpand(keyword: String): List<String> {
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

        val raw = postToGroqWithFallback(body)?.choices?.firstOrNull()?.message?.content
            ?: throw RuntimeException("пустой ответ от groq expand")

        val clean = raw.replace(Regex("```json|```"), "").trim()

        val variantsJson = Regex("\"variants\"\\s*:\\s*\\[([^]]*)]")
            .find(clean)?.groupValues?.get(1)
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

    // ─── Очередь и throttle ──────────────────────────────────────────────────────

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

    // ─── Основной вызов валидации ─────────────────────────────────────────────────

    private fun callGroq(
        messageText:            String,
        keyword:                String,
        contextMessages:        List<String>,
        recentLeads:            List<RecentLead> = emptyList(),
        businessContext:        String?          = null,
        respondToServiceOffers: Boolean          = false,
    ): ValidationResult {
        // Обрезаем сообщение до 600 символов — продающий пост выдаёт себя в первых строках.
        val messageShort = messageText.take(600)

        val contextBlock = if (contextMessages.isNotEmpty()) {
            val ctx = contextMessages.take(2).joinToString("\n") { "  - \"${it.take(100)}\"" }
            "\nКонтекст чата:\n$ctx"
        } else ""

        val leadsBlock = if (recentLeads.isNotEmpty()) {
            val leads = recentLeads.take(3).joinToString("\n") { l ->
                "  - @${l.authorUsername}: \"${l.messageText.take(60)}\""
            }
            "\nНедавние лиды (дубль = тот же автор + та же услуга + то же сообщение):\n$leads"
        } else ""

        val businessBlock = if (!businessContext.isNullOrBlank()) {
            "\nБизнес владельца (клиентов ищем для него):\n${businessContext.take(250)}"
        } else ""

        val serviceOffersRule = if (respondToServiceOffers) {
            "РЕЖИМ: принимать офферы. valid=true если автор предлагает свои услуги и ищет клиентов.\n\n"
        } else {
            """
ЗАДАЧА: определить, является ли сообщение реальным запросом клиента на услугу/исполнителя.

ГЛАВНЫЙ ТЕСТ — кто субъект действия:
- Автор говорит что ОН ДЕЛАЕТ / ПРЕДЛАГАЕТ / УМЕЕТ → ОФФЕР → valid=false
- Автор говорит что ЕМУ НУЖНО / КОГО ОН ИЩЕТ → ЛИД → valid=true

ОФФЕР — valid=false если есть хотя бы один признак:
- "я [профессия]": "я smm-менеджер", "я рилсмейкер", "я дизайнер", "меня зовут X и я Y"
- Продающий пост: риторический вопрос ("Тяжело вести блог?") + перечисление СВОИХ услуг + CTA
- Пакеты/тарифы услуг автора: нумерованные списки (1️⃣ 2️⃣ 3️⃣), "под ключ", "ведение аккаунта"
- Секции "ОБО МНЕ", "МОИ КЕЙСЫ", "МОЁ ПОРТФОЛИО", перечисление своего опыта
- "Занимаюсь X" / "Выполняю X" / "Делаю X" / "Работаю с X" / "Монтирую X" / "Создаю X" — глагол 1-го лица описывает свою деятельность
- Наличие "Портфолио" / ссылки на примеры работ (Яндекс.Диск, Behance, Google Drive, GitHub и т.п.)
- Хэштеги-самопрезентация (#помогу, #монтажер, #дизайнер, #фотограф, #копирайтер) в сочетании с описанием услуг
- "Ищу заказы / клиентов / проекты" — специалист ищет работу для себя
- Кастинг, найм сотрудников, реклама своих курсов, партнёрские ссылки

ЛИД — valid=true:
- Личный запрос на исполнителя: "ищу специалиста", "нужен человек", "посоветуйте кого-нибудь"
- Вопрос сообществу: "кто занимается X?", "есть кто делает Y?"
- Описание своей задачи/проблемы без предложения услуг

НЕЙТРАЛЬНО (не решает исход):
- "Пиши мне" / "напиши в лс" — смотри контекст ДО этой фразы
- Хештеги — только метки темы
- "Ищу разработчика" = ЛИД; "Ищу заказы" = ОФФЕР

ПРИМЕРЫ:
[ОФФЕР] "Тяжело вести блог?🥺 А что если делегировать: SMM под ключ, Reels от идеи до монтажа. Мои услуги🤳 1️⃣Стратегия 2️⃣Reels ведение 3️⃣SMM. ОБО МНЕ: опыт 3 года. КЕЙСЫ: привела 4 клиентов. Пиши @username"
→ {"valid":false,"reason":"Продающий пост SMM-специалиста: риторический вопрос + пакеты услуг автора + ОБО МНЕ/КЕЙСЫ","confidence":"high"}

[ОФФЕР] "Привет, меня зовут Маша и я рилсмейкер. Оказываю услуги по продвижению reels. Пиши @mashaageeva"
→ {"valid":false,"reason":"Самопрезентация специалиста — автор предлагает свои услуги","confidence":"high"}

[ОФФЕР] "#помогу #монтаж #монтажер #рилс Занимаюсь монтажем вертикальных видео (Reels, Short, TikTok). Работаю с русским и английским. Портфолио: disk.yandex.ru/... Тг @q"
→ {"valid":false,"reason":"'занимаюсь' + 'работаю с' + портфолио + хэштеги-самопрезентация — оффер монтажёра","confidence":"high"}

[ОФФЕР] "Делаю сайты под ключ на WordPress. Работаю с малым бизнесом. Примеры работ: behance.net/... Пишите в лс."
→ {"valid":false,"reason":"'делаю' + 'работаю с' + ссылка-портфолио — классический оффер разработчика","confidence":"high"}

[ЛИД] "Ребята, ищу SMM-специалиста для магазина одежды. Нужно вести инст, делать рилсы. Пишите в лс"
→ {"valid":true,"reason":"Владелец бизнеса ищет SMM-специалиста для себя — конкретный запрос заказчика","confidence":"high"}

[ЛИД] "Нужен контент-план для салона красоты, кто делает? Бюджет до 15к"
→ {"valid":true,"reason":"Владелец салона ищет специалиста по контент-плану с бюджетом","confidence":"high"}

"""
        }

        val prompt = """
${serviceOffersRule}Ключевое слово поиска: "$keyword"$contextBlock$leadsBlock$businessBlock

Сообщение:
"$messageShort"

Ответь строго JSON без текста вне JSON:
{"valid":true/false,"reason":"одно предложение","confidence":"low/medium/high"}
""".trimIndent()

        val body = mapOf(
            "model" to MAIN_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "Фильтруешь лиды в Telegram. Отвечай только JSON."),
                mapOf("role" to "user", "content" to prompt),
            ),
            "max_tokens" to 150,
            "temperature" to 0.0,
        )

        val content = postToGroqWithFallback(body)?.choices?.firstOrNull()?.message?.content
            ?: throw RuntimeException("пустой ответ от groq")

        return parseResult(content)
    }

    // ─── Fallback по списку ключей ────────────────────────────────────────────────

    /**
     * Перебирает ключи из [apiKeys] по порядку.
     * При 429 (rate limit) или 413 (payload/TPM exceeded) переходит к следующему ключу.
     * Остальные ошибки (400, 401, 500…) пробрасываются сразу без fallback.
     *
     * Пример env:
     *   GROQ_API_KEY="gsk_key1, gsk_key2, gsk_key3"
     */
    private fun postToGroqWithFallback(body: Map<String, Any>): GroqResponse? {
        var lastException: Exception? = null

        for ((index, key) in apiKeys.withIndex()) {
            try {
                return postToGroq(key, body)
            } catch (e: HttpClientErrorException) {
                val status = e.statusCode.value()
                if (status == 429 || status == 413) {
                    lastException = e
                    if (index < apiKeys.size - 1) {
                        log.warn("groq $status на ключе #${index + 1} — переключаемся на ключ #${index + 2}")
                    } else {
                        log.warn("groq $status на ключе #${index + 1} — все ${apiKeys.size} ключ(а/ей) исчерпаны")
                    }
                } else {
                    throw e
                }
            }
        }

        throw lastException ?: RuntimeException("нет доступных groq api ключей")
    }

    /**
     * Выполняет HTTP-запрос к Groq с конкретным ключом.
     */
    private fun postToGroq(key: String, body: Map<String, Any>): GroqResponse? {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(key)
        }
        return restTemplate.postForObject(
            GROQ_URL,
            HttpEntity(body, headers),
            GroqResponse::class.java,
        )
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