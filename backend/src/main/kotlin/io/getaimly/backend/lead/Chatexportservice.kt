package io.getaimly.backend.lead

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.getaimly.backend.bot.AimlyBot
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
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
    // true, если сообщение является пересланным (forward).
    // Определяем эвристически по HTML-структуре Telegram-экспорта.
    val isForwarded:    Boolean = false,
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
    val from:         String? = null,
    val from_id:      String? = null,
    val text:         Any?    = null,   // может быть String или List<Any>
    // Поля пересланных сообщений в Telegram JSON-экспорте
    val forwarded_from: String? = null,
    val forward_from:   String? = null,
)

// ─── Сервис ───────────────────────────────────────────────────────────────────

@Service
class ChatExportService(
    private val leadService: LeadService,
    private val keywordRepo: KeywordRepository,
    private val objectMapper: ObjectMapper,
    // @Lazy чтобы разорвать циклическую зависимость: AimlyBot → LeadService → ChatExportService → AimlyBot
    @Lazy private val bot: AimlyBot,
) {
    private val log = LoggerFactory.getLogger(ChatExportService::class.java)

    private val ISO_FMT      = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val ISO_FMT_ZONE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private val HTML_FMT     = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    private val HTML_FMT2    = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

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
                val date = parseDate(m.date) ?: return@mapNotNull null

                // В JSON-экспорте пересланные сообщения имеют поле forwarded_from или forward_from
                val isForwarded = !m.forwarded_from.isNullOrBlank() || !m.forward_from.isNullOrBlank()

                ParsedMessage(
                    messageId      = m.id,
                    authorName     = m.from?.trim() ?: "Неизвестный",
                    authorUsername = "",
                    text           = text.trim(),
                    date           = date,
                    isForwarded    = isForwarded,
                )
            }

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
     * Пересланные сообщения имеют класс "forwarded" и/или содержат блок .forwarded внутри.
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
        val headerBlock = Regex("""class="page_header"[^>]*>(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: ""
        val bold = Regex("""class="[^"]*bold[^"]*"[^>]*>([^<]+)<""")
            .find(headerBlock)?.groupValues?.get(1)?.trim()
        if (!bold.isNullOrBlank()) return bold

        val title = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()
        return title?.ifBlank { null } ?: "Импорт (HTML)"
    }

    private fun parseHtmlMessages(html: String): List<ParsedMessage> {
        val messages = mutableListOf<ParsedMessage>()

        val blockStartRe = Regex("""<div[^>]+class="[^"]*message\s+default[^"]*"[^>]+id="message(\d+)"[^>]*>""")

        val starts = blockStartRe.findAll(html).toList()

        for (i in starts.indices) {
            val match    = starts[i]
            val msgIdStr = match.groupValues[1]
            val msgId    = msgIdStr.toLongOrNull() ?: 0L
            val blockStart = match.range.first

            val blockEnd = if (i + 1 < starts.size) starts[i + 1].range.first else html.length
            val block    = html.substring(blockStart, blockEnd)

            val date       = extractHtmlDate(block)   ?: continue
            val authorName = extractHtmlAuthor(block)
            val text       = extractHtmlText(block)
            if (text.isBlank()) continue

            val isForwarded = block.contains("""class="forwarded"""")

            messages += ParsedMessage(
                messageId      = msgId,
                authorName     = authorName,
                authorUsername = "",
                text           = text.trim(),
                date           = date,
                isForwarded    = isForwarded,
            )
        }

        return messages
    }

    /** Дата из: <div class="... date ..." title="дд.мм.гггг чч:мм:сс"> */
    private fun extractHtmlDate(block: String): LocalDateTime? {
        val title = Regex("""class="[^"]*date[^"]*"[^>]+title="([^"]+)"""")
            .find(block)?.groupValues?.get(1) ?: return null
        return parseDate(title.trim())
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

            // Пересланные сообщения пропускаем — они не являются запросами от реального автора
            if (msg.isForwarded) {
                log.debug("[IMPORT] Пересланное сообщение пропущено: id=${msg.messageId} author=${msg.authorName}")
                skipped++
                continue
            }

            val matchedKeyword = findKeyword(msg.text, allKeywords) ?: continue

            val contextMessages = messages
                .subList(maxOf(0, i - 3), i)
                .filter { !it.isForwarded }
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
                source          = LeadSource.MANUAL_EXPORT,
                messageDate     = msg.date,
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

        // Отправляем одно сводное уведомление вместо серии поштучных
        user.telegramId?.let { tgId ->
            runCatching {
                bot.notifyExportSummary(
                    telegramChatId = tgId,
                    chatTitle      = chatTitle,
                    matchedLeads   = matched,
                    skippedLeads   = skipped,
                )
            }.onFailure {
                log.warn("[IMPORT] Ошибка отправки сводки в бот: userId=${user.id} причина=${it.message}")
            }
        }

        return ImportResultDto(chatTitle, messages.size, matched, skipped, format)
    }

    // ─── Утилиты ─────────────────────────────────────────────────────────────

    private fun findKeyword(text: String, keywords: List<String>): String? {
        val lower = text.lowercase()
        return keywords.firstOrNull { lower.contains(it) }
    }

    private fun parseDate(raw: String): LocalDateTime? {
        if (raw.isBlank()) return null
        val s = raw.trim()
        for (fmt in listOf(ISO_FMT, ISO_FMT_ZONE, HTML_FMT, HTML_FMT2)) {
            try {
                return LocalDateTime.parse(s.take(19), fmt)
            } catch (_: DateTimeParseException) { /* попробуем следующий */ }
        }
        return null
    }
}