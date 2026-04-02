package io.getaimly.backend.bot

import io.getaimly.backend.lead.ChatExportService
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockMultipartFile
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val MAX_FILE_SIZE_MB = 50L
private const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024

class BotChatExportHandler(
    private val sender: BotSender,
    private val sessions: ConcurrentHashMap<Long, UserSession>,
    private val userRepository: UserRepository,
    private val exportService: ChatExportService,
    private val telegramClient: TelegramClient,
    private val botToken: String,
) {
    private val log = LoggerFactory.getLogger(BotChatExportHandler::class.java)

    private val worker = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "bot-export-worker").also { it.isDaemon = true }
    }

    /**
     * Показываем экран с инструкцией и просим прислать файл.
     */
    fun showExportScreen(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        if (user.subscriptionStatus !in setOf("ACTIVE", "TRIAL")) {
            sender.editText(
                chatId, msgId,
                "🔒 *Анализ экспорта чата*\n\n" +
                        "Эта функция доступна только при активной подписке.\n\n" +
                        "Оформите подписку, чтобы анализировать историю чатов и находить лиды.",
                keyboard(
                    row(btn("💳 Оплатить подписку", "payment:plans")),
                    row(btn("◀️ Главное меню", "menu:back")),
                ),
                parseMarkdown = true,
            )
            return
        }

        val kwCount = 0 // проверим при получении файла
        sessions[chatId] = UserSession(
            step  = BotStep.WAITING_CHAT_EXPORT_FILE,
            msgId = msgId,
        )

        sender.editText(
            chatId, msgId,
            "📤 *Анализ экспорта чата*\n\n" +
                    "Загрузите экспорт переписки из Telegram Desktop — бот найдёт в нём лиды по вашим ключевым словам.\n\n" +
                    "📋 *Как получить экспорт:*\n" +
                    "1. Откройте нужный чат в *Telegram Desktop* (Windows/Mac/Linux)\n" +
                    "2. Нажмите на ⋮ (три точки) в правом верхнем углу\n" +
                    "3. Выберите *«Экспорт истории чата»*\n" +
                    "4. Выберите формат: *HTML* или *JSON*\n" +
                    "5. Снимите галочки с медиафайлов для ускорения\n" +
                    "6. Нажмите *«Экспорт»*\n" +
                    "7. Отправьте полученный файл (`result.json` или `messages.html`) сюда\n\n" +
                    "⚠️ Максимальный размер файла: *${MAX_FILE_SIZE_MB} МБ*\n" +
                    "✅ Поддерживаемые форматы: `.html`, `.htm`, `.json`",
            keyboard(
                row(btn("❌ Отмена", "menu:back")),
            ),
            parseMarkdown = true,
        )

        log.info("[BOT][EXPORT] Показан экран экспорта: chatId=$chatId tgUserId=$tgUserId")
    }

    /**
     * Обрабатываем входящий документ (файл) от пользователя.
     * Вызывается из AimlyBot.handleMessage когда сессия в шаге WAITING_CHAT_EXPORT_FILE.
     */
    fun handleFileUpload(
        chatId: Long,
        from: org.telegram.telegrambots.meta.api.objects.User,
        document: org.telegram.telegrambots.meta.api.objects.Document,
    ) {
        val session    = sessions.remove(chatId)
        val savedMsgId = session?.msgId ?: 0
        val tgUser     = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        val user = userRepository.findByTelegramId(from.id).orElse(null)
        if (user == null) {
            log.warn("[BOT][EXPORT] Не авторизован при загрузке файла: chatId=$chatId tgId=${from.id}")
            sender.sendText(chatId, "Нужно войти. /start")
            return
        }

        if (user.subscriptionStatus !in setOf("ACTIVE", "TRIAL")) {
            sender.sendText(chatId, "❌ Для анализа экспорта необходима активная подписка.")
            return
        }

        val fileName = document.fileName ?: ""
        val fileSize = document.fileSize ?: 0L

        log.info("[BOT][EXPORT] Файл получен: $tgUser fileName=\"$fileName\" size=$fileSize")

        // Проверка формата по имени файла
        val lowerName = fileName.lowercase()
        val isJson = lowerName.endsWith(".json")
        val isHtml = lowerName.endsWith(".html") || lowerName.endsWith(".htm")

        if (!isJson && !isHtml) {
            log.warn("[BOT][EXPORT] Неверный формат файла: $tgUser fileName=\"$fileName\"")
            if (savedMsgId != 0) {
                sender.editText(
                    chatId, savedMsgId,
                    "❌ *Неверный формат файла*\n\n" +
                            "Поддерживаются только файлы:\n" +
                            "• `.json` — JSON-экспорт Telegram Desktop\n" +
                            "• `.html` / `.htm` — HTML-экспорт Telegram Desktop\n\n" +
                            "Повторите экспорт и выберите нужный формат.",
                    keyboard(
                        row(btn("📤 Попробовать снова", "export:start")),
                        row(btn("◀️ Главное меню", "menu:back")),
                    ),
                    parseMarkdown = true,
                )
            } else {
                sender.sendText(
                    chatId,
                    "❌ Неверный формат файла. Поддерживаются только .json, .html, .htm",
                    keyboard(
                        row(btn("📤 Попробовать снова", "export:start")),
                        row(btn("◀️ Главное меню", "menu:back")),
                    ),
                )
            }
            return
        }

        // Проверка размера
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            log.warn("[BOT][EXPORT] Файл слишком большой: $tgUser size=$fileSize")
            sender.sendText(
                chatId,
                "❌ Файл слишком большой (${fileSize / 1024 / 1024} МБ).\n\n" +
                        "Максимальный размер: ${MAX_FILE_SIZE_MB} МБ.\n\n" +
                        "Попробуйте экспортировать меньший диапазон дат.",
                keyboard(row(btn("◀️ Главное меню", "menu:back"))),
            )
            return
        }

        // Сообщение «обрабатываем»
        val processingMsgId = if (savedMsgId != 0) {
            sender.editText(
                chatId, savedMsgId,
                "⏳ *Анализируем файл...*\n\n" +
                        "Файл: $fileName\n" +
                        "Ищем лиды по вашим ключевым словам.\n\n" +
                        "_Это может занять несколько секунд._",
                parseMarkdown = true,
            )
            savedMsgId
        } else {
            sender.sendTextAndGetId(
                chatId,
                "⏳ Анализируем файл \"$fileName\"...\n\nЭто может занять несколько секунд.",
            ) ?: 0
        }

        val fileId   = document.fileId
        val capturedUser = user

        worker.submit {
            try {
                log.info("[BOT][EXPORT] Начало загрузки файла из Telegram: $tgUser fileId=$fileId")

                // Получаем путь к файлу через Telegram API
                val getFile = GetFile(fileId)
                val tgFile  = telegramClient.execute(getFile)
                val filePath = tgFile.filePath

                if (filePath.isNullOrBlank()) {
                    log.error("[BOT][EXPORT] Telegram вернул пустой filePath: $tgUser fileId=$fileId")
                    sender.editText(
                        chatId, processingMsgId,
                        "❌ Не удалось получить файл от Telegram. Попробуйте ещё раз.",
                        keyboard(row(btn("◀️ Главное меню", "menu:back"))),
                    )
                    return@submit
                }

                // Скачиваем файл напрямую по URL
                val downloadUrl = "https://api.telegram.org/file/bot$botToken/$filePath"
                val fileBytes   = URI.create(downloadUrl).toURL().readBytes()

                log.info("[BOT][EXPORT] Файл скачан: $tgUser size=${fileBytes.size}")

                // Определяем content type
                val contentType = when {
                    isJson -> "application/json"
                    else   -> "text/html"
                }

                // Создаём MockMultipartFile для ChatExportService
                val multipartFile = MockMultipartFile(
                    "file",
                    fileName,
                    contentType,
                    fileBytes,
                )

                val result = exportService.processExport(capturedUser, multipartFile)

                log.info(
                    "[BOT][EXPORT] Завершён: userId=${capturedUser.id} email=${capturedUser.email} " +
                            "chat=\"${result.chatTitle}\" total=${result.totalMessages} " +
                            "matched=${result.matchedLeads} skipped=${result.skippedLeads}"
                )

                val formatLabel = when (result.format) {
                    "json" -> "JSON"
                    "html" -> "HTML"
                    else   -> result.format.uppercase()
                }

                val resultText = buildString {
                    append("✅ *Анализ завершён!*\n\n")
                    append("💬 Чат: *${result.chatTitle.md()}*\n")
                    append("📄 Формат: $formatLabel\n\n")
                    append("📊 *Результаты:*\n")
                    append("• Всего сообщений: ${result.totalMessages}\n")
                    append("• Найдено лидов: *${result.matchedLeads}*\n")
                    if (result.skippedLeads > 0) {
                        append("• Пропущено (дубли): ${result.skippedLeads}\n")
                    }
                    append("\n")
                    if (result.matchedLeads > 0) {
                        append("🎯 Лиды добавлены в ваш список. Проверьте раздел «Лиды».")
                    } else {
                        append("😔 Подходящих лидов по вашим ключевым словам не найдено.\n\n")
                        append("💡 Попробуйте добавить больше ключевых слов или проверьте другой чат.")
                    }
                }

                sender.editText(
                    chatId, processingMsgId,
                    resultText,
                    keyboard(
                        if (result.matchedLeads > 0) row(btn("📋 Посмотреть лиды", "menu:leads")) else null,
                        row(btn("📤 Загрузить ещё файл", "export:start")),
                        row(btn("◀️ Главное меню", "menu:back")),
                    ),
                    parseMarkdown = true,
                )

            } catch (e: IllegalArgumentException) {
                log.warn("[BOT][EXPORT] Ошибка валидации: $tgUser — ${e.message}")
                sender.editText(
                    chatId, processingMsgId,
                    "❌ *Ошибка обработки файла*\n\n${e.message}",
                    keyboard(
                        row(btn("📤 Попробовать снова", "export:start")),
                        row(btn("◀️ Главное меню", "menu:back")),
                    ),
                    parseMarkdown = true,
                )
            } catch (e: Exception) {
                log.error("[BOT][EXPORT] Критическая ошибка: $tgUser — ${e.message}", e)
                sender.editText(
                    chatId, processingMsgId,
                    "❌ Ошибка при обработке файла. Попробуйте позже.",
                    keyboard(
                        row(btn("📤 Попробовать снова", "export:start")),
                        row(btn("◀️ Главное меню", "menu:back")),
                    ),
                )
            }
        }
    }

    /**
     * Пользователь прислал текст вместо файла в режиме ожидания экспорта.
     */
    fun handleWrongInput(chatId: Long) {
        sender.sendText(
            chatId,
            "⚠️ Пожалуйста, отправьте *файл* экспорта (.json или .html).\n\n" +
                    "Или нажмите «Отмена» для возврата в меню.",
            keyboard(row(btn("❌ Отмена", "menu:back"))),
            parseMarkdown = true,
        )
    }

    // Вспомогательная функция для фильтрации null-строк при создании клавиатуры
    private fun keyboard(vararg rows: InlineKeyboardRow?): InlineKeyboardMarkup? {
        val filteredRows = rows.filterNotNull()
        return if (filteredRows.isEmpty()) null
        else InlineKeyboardMarkup(filteredRows)
    }
}

// Вспомогательный импорт (чтобы компилятор не ругался на неиспользуемый импорт)
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup