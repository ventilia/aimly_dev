package io.getaimly.backend.lead

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// ─── DTOs для результата импорта ─────────────────────────────────────────────

data class ImportResultDto(
    val chatTitle:     String,
    val totalMessages: Int,
    val matchedLeads:  Int,
    val skippedLeads:  Int,      // дубли или неактивная подписка
    val format:        String,   // "html" | "json"
)

// ─── Внутренние модели ────────────────────────────────────────────────────────

data class ParsedMessage(
    val messageId:      Long,
    val authorName:     String,
    val authorUsername: String,
    val text:           String,
    val date:           LocalDateTime,
)

// ─── Jackson-маппинг для Telegram JSON-экспорта ───────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgExportRoot(
    val name: String = "",
    val type: String = "",
    val id:   Long   = 0,
    val messages: List<TgExportMessage> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgExportMessage(
    val id:           Long    = 0,
    val type:         String  = "",
    val date:         String  = "",
    val date_unixtime: String? = null,  // unix-timestamp как резервный источник
    val from:         String? = null,
    val from_id:      String? = null,
    val text:         Any?    = null,   // может быть String или List<Any>
)

// ─── Сервис ───────────────────────────────────────────────────────────────────

@Service
class ChatExportService(
    private val leadService: LeadService,
    private val keywordRepo: KeywordRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ChatExportService::class.java)


    private val PARSERS: List<(String) -> LocalDateTime?> = listOf(
        // 1. ISO с offset: 2024-01-15T14:32:00+03:00 / 2024-01-15T14:32:00Z
        //    Парсим через OffsetDateTime → нормализуем к серверному времени (без смещения)
        //    Важно: обрезать нельзя — теряется зона
        { s ->
            // FIX: contains(Char, startIndex) не существует, используем indexOf
            if (s.length > 19 && (s.indexOf('+', startIndex = 10) != -1 || s.endsWith('Z'))) {
                tryParse { OffsetDateTime.parse(s).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime() }
            } else null
        },

        // 2. ISO без зоны, с секундами: 2024-01-15T14:32:00
        { s ->
            if (s.length >= 19 && s[10] == 'T') {
                tryParse { LocalDateTime.parse(s.take(19), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) }
            } else null
        },

        // 3. ISO без зоны, без секунд: 2024-01-15T14:32
        { s ->
            if (s.length >= 16 && s[10] == 'T') {
                tryParse { LocalDateTime.parse(s.take(16), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")) }
            } else null
        },

        // 4. HTML формат с секундами: 15.01.2024 14:32:00
        { s ->
            if (s.length >= 19 && s[2] == '.' && s[5] == '.') {
                tryParse { LocalDateTime.parse(s.take(19), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) }
            } else null
        },

        // 5. HTML формат без секунд: 15.01.2024 14:32
        { s ->
            if (s.length >= 16 && s[2] == '.' && s[5] == '.') {
                tryParse { LocalDateTime.parse(s.take(16), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) }
            } else null
        },

        // 6. ISO-подобный с пробелом, с секундами: 2024-01-15 14:32:00
        { s ->
            if (s.length >= 19 && s[10] == ' ' && s[4] == '-') {
                tryParse { LocalDateTime.parse(s.take(19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }
            } else null
        },

        // 7. ISO-подобный с пробелом, без секунд: 2024-01-15 14:32
        { s ->
            if (s.length >= 16 && s[10] == ' ' && s[4] == '-') {
                tryParse { LocalDateTime.parse(s.take(16), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }
            } else null
        },
    )

    private inline fun tryParse(block: () -> LocalDateTime): LocalDateTime? =
        try { block() } catch (_: DateTimeParseException) { null } catch (_: Exception) { null }

    /**
     * Основная точка входа: определяет формат, парсит, прогоняет через LeadService.
     */
    fun processExport(
        user: io.getaimly.backend.user.User,
        file: MultipartFile,
    ): ImportResultDto {
        val filename    = file.originalFilename?.lowercase() ?: ""
        val contentType = file.contentType?.lowercase() ?: ""

        val isJson = filename.endsWith(".json") || contentType.contains("json")
        val isHtml = filename.endsWith(".html") || filename.endsWith(".htm") || contentType.contains("html")

        if (!isJson && !isHtml) {
            throw IllegalArgumentException("Неподдерживаемый формат файла. Экспортируйте чат в формате HTML или JSON.")
        }

        if (file.size > 100 * 1024 * 1024) {
            throw IllegalArgumentException("Файл слишком большой (максимум 100 МБ).")
        }

        val content = file.bytes.toString(Charsets.UTF_8)

        return if (isJson) processJsonExport(user, content)
        else              processHtmlExport(user, content)
    }

    // ─── JSON-формат ─────────────────────────────────────────────────────────

    private fun processJsonExport(
        user:    io.getaimly.backend.user.User,
        content: String,
    ): ImportResultDto {
        val root = try {
            objectMapper.readValue(content, TgExportRoot::class.java)
        } catch (e: Exception) {
            log.warn("[IMPORT] Ошибка парсинга JSON: userId=${user.id} — ${e.message}")
            throw IllegalArgumentException("Не удалось распарсить JSON-файл. Убедитесь, что это экспорт Telegram Desktop.")
        }

        val chatTitle = root.name.ifBlank { "Импорт (JSON)" }
        val messages  = parseJsonMessages(root)

        log.info("[IMPORT] JSON: userId=${user.id} email=${user.email} chat=\"$chatTitle\" messages=${messages.size}")

        return processMessages(user, messages, chatTitle, "json")
    }

    private fun parseJsonMessages(root: TgExportRoot): List<ParsedMessage> =
        root.messages
            .filter { it.type == "message" }
            .mapNotNull { m ->
                val text = extractJsonText(m.text) ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null

                // Пробуем date-поле, при неудаче — date_unixtime
                val date = parseDate(m.date)
                    ?: parseUnixTimestamp(m.date_unixtime)
                    ?: run {
                        log.debug("[IMPORT] Не удалось распарсить дату: id=${m.id} date=\"${m.date}\" unixtime=${m.date_unixtime}")
                        return@mapNotNull null
                    }

                ParsedMessage(
                    messageId      = m.id,
                    authorName     = m.from?.trim() ?: "Неизвестный",
                    authorUsername = extractUsername(m.from_id),
                    text           = text.trim(),
                    date           = date,
                )
            }

    /**
     * Извлекает username из from_id.
     * В JSON-экспорте from_id может иметь вид "user123456789" или быть числом.
     * Username реально не хранится в экспорте — возвращаем пустую строку.
     */
    private fun extractUsername(fromId: String?): String = ""

    @Suppress("UNCHECKED_CAST")
    private fun extractJsonText(raw: Any?): String? = when (raw) {
        null      -> null
        is String -> raw.takeIf { it.isNotBlank() }
        is List<*> -> raw.joinToString("") { part ->
            when (part) {
                is String     -> part
                is Map<*, *>  -> (part["text"] as? String) ?: ""
                else          -> ""
            }
        }.takeIf { it.isNotBlank() }
        else -> null
    }

    // ─── HTML-формат (без внешних зависимостей) ───────────────────────────────

    /**
     * Парсим Telegram Desktop HTML-экспорт стандартными средствами JVM.
     *
     * Telegram Desktop генерирует валидный HTML5 с предсказуемой структурой:
     *   <div class="message default clearfix" id="messageNNN">
     *     <div class="from_name">Имя</div>
     *     <div class="pull_right date details" title="дд.мм.гггг чч:мм:сс">...</div>
     *     <div class="text">текст</div>
     *   </div>
     *
     * Используем regex-парсинг по блокам — надёжный подход для фиксированного формата.
     */
    private fun processHtmlExport(
        user:    io.getaimly.backend.user.User,
        content: String,
    ): ImportResultDto {
        val chatTitle = extractHtmlChatTitle(content)
        val messages  = parseHtmlMessages(content)

        log.info("[IMPORT] HTML: userId=${user.id} email=${user.email} chat=\"$chatTitle\" messages=${messages.size}")

        return processMessages(user, messages, chatTitle, "html")
    }

    /** Извлекает заголовок чата из .page_header .bold */
    private fun extractHtmlChatTitle(html: String): String {
        // <div class="bold">Название чата</div>  — в секции page_header
        val headerBlock = Regex("""class="page_header"[^>]*>(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: ""
        val bold = Regex("""class="[^"]*bold[^"]*"[^>]*>([^<]+)<""")
            .find(headerBlock)?.groupValues?.get(1)?.trim()
        if (!bold.isNullOrBlank()) return bold

        // fallback: <title>...</title>
        val title = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()
        return title?.ifBlank { null } ?: "Импорт (HTML)"
    }

    private fun parseHtmlMessages(html: String): List<ParsedMessage> {
        val messages = mutableListOf<ParsedMessage>()

        // Ищем все открывающие теги div.message.default
        val blockStartRe = Regex("""<div[^>]+class="[^"]*message\s+default[^"]*"[^>]+id="message(\d+)"[^>]*>""")

        val starts = blockStartRe.findAll(html).toList()

        for (i in starts.indices) {
            val match    = starts[i]
            val msgId    = match.groupValues[1].toLongOrNull() ?: 0L
            val blockStart = match.range.first

            // Конец блока — до начала следующего message-блока или конца файла
            val blockEnd = if (i + 1 < starts.size) starts[i + 1].range.first else html.length
            val block    = html.substring(blockStart, blockEnd)

            val date       = extractHtmlDate(block)   ?: continue
            val authorName = extractHtmlAuthor(block)
            val text       = extractHtmlText(block)
            if (text.isBlank()) continue

            messages += ParsedMessage(
                messageId      = msgId,
                authorName     = authorName,
                authorUsername = "",
                text           = text.trim(),
                date           = date,
            )
        }

        return messages
    }

    /**
     * Дата из: <div class="... date ..." title="дд.мм.гггг чч:мм:сс">
     *
     * Telegram Desktop записывает дату в атрибут title в нескольких вариантах:
     *   "15.01.2024 14:32:00"  — основной
     *   "15.01.2024 14:32"     — без секунд (старые сборки)
     * Передаём строку напрямую в parseDate() без обрезки.
     */
    private fun extractHtmlDate(block: String): LocalDateTime? {
        val title = Regex("""class="[^"]*date[^"]*"[^>]+title="([^"]+)"""")
            .find(block)?.groupValues?.get(1) ?: return null
        val raw = title.trim()
        val result = parseDate(raw)
        if (result == null) {
            log.debug("[IMPORT][HTML] Не удалось распарсить дату: \"$raw\"")
        }
        return result
    }

    /** Автор из: <div class="from_name">Имя</div> */
    private fun extractHtmlAuthor(block: String): String {
        val raw = Regex("""class="from_name"[^>]*>([^<]+)<""")
            .find(block)?.groupValues?.get(1) ?: return "Неизвестный"
        return unescapeHtml(raw.trim())
    }

    /**
     * Текст из: <div class="text">…</div>
     * Telegram может вставлять внутри ссылки, эмодзи-изображения и форматирование —
     * убираем все теги и декодируем HTML-entities.
     */
    private fun extractHtmlText(block: String): String {
        val textBlock = Regex("""class="text"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1) ?: return ""
        val stripped = textBlock.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return unescapeHtml(stripped)
    }

    /** Минимальный декодер HTML-entities, достаточный для Telegram-экспорта */
    private fun unescapeHtml(s: String): String = s
        .replace("&amp;",  "&")
        .replace("&lt;",   "<")
        .replace("&gt;",   ">")
        .replace("&quot;", "\"")
        .replace("&#39;",  "'")
        .replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);"))  { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }

    // ─── Общая обработка сообщений ───────────────────────────────────────────

    private fun processMessages(
        user:      io.getaimly.backend.user.User,
        messages:  List<ParsedMessage>,
        chatTitle: String,
        format:    String,
    ): ImportResultDto {
        if (messages.isEmpty()) {
            return ImportResultDto(chatTitle, 0, 0, 0, format)
        }

        val kwEntities  = keywordRepo.findByUserIdAndIsActiveTrue(user.id)
        val allKeywords = kwEntities.flatMap { it.allVariants() }.map { it.lowercase() }.distinct()

        if (allKeywords.isEmpty()) {
            throw IllegalArgumentException("У вас нет активных ключевых слов. Добавьте хотя бы одно ключевое слово для поиска.")
        }

        val importChatLink = "import://$chatTitle"
        var matched = 0
        var skipped = 0

        for (i in messages.indices) {
            val msg = messages[i]
            val matchedKeyword = findKeyword(msg.text, allKeywords) ?: continue

            val contextMessages = messages
                .subList(maxOf(0, i - 3), i)
                .map { it.text.take(300) }

            val req = IncomingMessageRequest(
                userId          = user.id,
                tgMessageId     = msg.messageId,
                chatTgId        = 0L,
                chatLink        = importChatLink,
                chatTitle       = chatTitle,
                authorName      = msg.authorName.take(255),
                authorUsername  = msg.authorUsername.take(255),
                messageText     = msg.text.take(4000),
                messageLink     = "",
                matchedKeyword  = matchedKeyword,
                contextMessages = contextMessages,
                isHistorical    = true,
            )

            try {
                leadService.processIncomingMessage(req)
                matched++
            } catch (e: Exception) {
                log.debug("[IMPORT] Пропущено сообщение id=${msg.messageId}: ${e.message}")
                skipped++
            }
        }

        log.info(
            "[IMPORT] Завершён: userId=${user.id} email=${user.email} " +
                    "chat=\"$chatTitle\" total=${messages.size} matched=$matched skipped=$skipped"
        )

        return ImportResultDto(chatTitle, messages.size, matched, skipped, format)
    }

    // ─── Утилиты ─────────────────────────────────────────────────────────────

    private fun findKeyword(text: String, keywords: List<String>): String? {
        val lower = text.lowercase()
        return keywords.firstOrNull { lower.contains(it) }
    }

    /**
     * Универсальный парсер дат для Telegram Desktop экспортов.
     *
     * Пробует каждый парсер из списка PARSERS по порядку.
     * Каждый парсер самостоятельно проверяет признаки своего формата
     * (длину, символы-разделители) перед попыткой парсинга — это
     * исключает ложные срабатывания и лишние исключения.
     */
    fun parseDate(raw: String): LocalDateTime? {
        if (raw.isBlank()) return null
        val s = raw.trim()
        for (parser in PARSERS) {
            val result = parser(s)
            if (result != null) return result
        }
        return null
    }

    /**
     * Парсинг unix-timestamp из поля date_unixtime.
     * Telegram Desktop записывает его как строку с числом секунд с эпохи.
     */
    private fun parseUnixTimestamp(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        val epochSeconds = raw.trim().toLongOrNull() ?: return null
        return try {
            LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC)
        } catch (_: Exception) {
            null
        }
    }
}