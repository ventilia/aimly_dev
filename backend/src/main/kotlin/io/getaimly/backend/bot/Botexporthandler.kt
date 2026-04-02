package io.getaimly.backend.bot

import io.getaimly.backend.lead.ChatExportService
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockMultipartFile
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.objects.Document
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Обрабатывает импорт экспорта Telegram Desktop (.json / .html),
 * отправленного пользователем прямо в бот.
 *
 * Сценарий:
 *  1. Пользователь нажимает «📤 Импорт экспорта» в меню чатов
 *  2. Бот переходит в шаг WAITING_EXPORT_FILE и ждёт документа
 *  3. Пользователь пересылает файл (export.json / messages.html)
 *  4. Хендлер скачивает файл через Bot API, передаёт в ChatExportService
 *  5. Результат импорта отображается в том же «главном» сообщении
 */
class BotExportHandler(
    private val sender: BotSender,
    private val sessions: ConcurrentHashMap<Long, UserSession>,
    private val userRepository: UserRepository,
    private val exportService: ChatExportService,
    private val telegramClient: TelegramClient,
    private val botToken: String,
) {

    private val log = LoggerFactory.getLogger(BotExportHandler::class.java)

    // ─── Начало flow ─────────────────────────────────────────────────────────

    fun startImportFlow(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][EXPORT] Не авторизован: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        if (user.subscriptionStatus !in setOf("ACTIVE", "TRIAL")) {
            log.warn("[BOT][EXPORT] Нет подписки: userId=${user.id} email=${user.email}")
            sender.editText(
                chatId, msgId,
                "❌ *Импорт недоступен*\n\n" +
                        "Для импорта экспортов Telegram необходима активная подписка.",
                keyboard(
                    row(btn("💳 Оформить подписку", "payment:plans")),
                    row(btn("◀️ Назад", "menu:chats")),
                ),
                parseMarkdown = true,
            )
            return
        }

        log.info("[BOT][EXPORT] Начало импорта: userId=${user.id} email=${user.email}")

        sessions[chatId] = UserSession(step = BotStep.WAITING_EXPORT_FILE, msgId = msgId)

        sender.editText(
            chatId, msgId,
            "📤 *Импорт экспорта Telegram*\n\n" +
                    "Отправьте файл экспорта закрытого чата.\n\n" +
                    "Как получить файл:\n" +
                    "1. Откройте нужный чат в *Telegram Desktop*\n" +
                    "2. Нажмите ⋮ → *Экспортировать историю чата*\n" +
                    "3. Выберите формат *JSON* или *HTML*\n" +
                    "4. Отправьте полученный файл сюда\n\n" +
                    "📎 Поддерживаемые форматы: `.json`, `.html`\n" +
                    "📦 Максимальный размер: *100 МБ*\n\n" +
                    "⚠️ Бот найдёт в истории сообщения, содержащие ваши ключевые слова.",
            keyboard(row(btn("❌ Отмена", "menu:chats"))),
            parseMarkdown = true,
        )
    }

    // ─── Обработка входящего документа ───────────────────────────────────────

    fun handleDocument(
        chatId: Long,
        tgUserId: Long,
        document: Document,
    ) {
        val session = sessions.remove(chatId)
        if (session?.step != BotStep.WAITING_EXPORT_FILE) {
            log.debug("[BOT][EXPORT] Документ вне сессии экспорта: chatId=$chatId")
            return
        }

        val savedMsgId = session.msgId

        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][EXPORT] Не авторизован при получении файла: chatId=$chatId tgId=$tgUserId")
            sender.sendText(chatId, "Нужно войти. /start")
            return
        }

        val filename = document.fileName ?: "export"
        val mimeType = document.mimeType ?: "application/octet-stream"
        val fileSize = document.fileSize ?: 0L

        log.info(
            "[BOT][EXPORT] Файл получен: userId=${user.id} email=${user.email} " +
                    "filename=\"$filename\" size=$fileSize mime=$mimeType"
        )

        // Проверка размера (Telegram Bot API пропускает максимум 20 МБ через getFile,
        // для файлов больше — рекомендуем веб-интерфейс)
        if (fileSize > 20 * 1024 * 1024) {
            log.warn("[BOT][EXPORT] Файл слишком большой для Bot API: userId=${user.id} size=$fileSize")
            val failText = "⚠️ *Файл слишком большой*\n\n" +
                    "Telegram Bot API позволяет скачивать файлы до *20 МБ*.\n\n" +
                    "Для больших экспортов воспользуйтесь *веб-версией AIMLY* — " +
                    "там лимит составляет 100 МБ.\n\n" +
                    "🌐 ${BotAuthHandler.SITE_URL}"
            if (savedMsgId != 0) {
                sender.editText(
                    chatId, savedMsgId, failText,
                    keyboard(
                        row(btn("🔄 Попробовать снова", "export:start")),
                        row(btn("◀️ Назад к чатам", "menu:chats")),
                    ),
                    parseMarkdown = true,
                )
            } else {
                sender.sendText(chatId, failText, parseMarkdown = true)
            }
            return
        }

        // Показываем прогресс
        if (savedMsgId != 0) {
            sender.editText(
                chatId, savedMsgId,
                "⏳ *Обрабатываем файл…*\n\n" +
                        "Скачиваем и разбираем `$filename`.\n" +
                        "Это может занять несколько секунд.",
                parseMarkdown = true,
            )
        } else {
            sender.sendText(chatId, "⏳ Обрабатываем `$filename`…", parseMarkdown = true)
        }

        // Скачиваем файл через Bot API
        val fileBytes = runCatching { downloadFile(document.fileId) }.getOrElse { e ->
            log.warn("[BOT][EXPORT] Ошибка скачивания: userId=${user.id} fileId=${document.fileId} причина=${e.message}")
            val errText = "❌ *Не удалось скачать файл*\n\n${e.message}\n\nПопробуйте ещё раз."
            if (savedMsgId != 0) {
                sender.editText(
                    chatId, savedMsgId, errText,
                    keyboard(
                        row(btn("🔄 Попробовать снова", "export:start")),
                        row(btn("◀️ Назад", "menu:chats")),
                    ),
                    parseMarkdown = true,
                )
            } else {
                sender.sendText(chatId, errText, parseMarkdown = true)
            }
            return
        }

        // Оборачиваем в MultipartFile-совместимый объект и передаём в сервис
        val multipart = MockMultipartFile(
            "file",
            filename,
            mimeType,
            fileBytes,
        )

        runCatching {
            exportService.processExport(user, multipart)
        }.onSuccess { result ->
            log.info(
                "[BOT][EXPORT] ✅ Импорт завершён: userId=${user.id} email=${user.email} " +
                        "chat=\"${result.chatTitle}\" total=${result.totalMessages} " +
                        "matched=${result.matchedLeads} skipped=${result.skippedLeads}"
            )

            val successText = buildString {
                append("✅ *Импорт завершён!*\n\n")
                append("📁 Чат: *${result.chatTitle.md()}*\n")
                append("📨 Сообщений обработано: *${result.totalMessages}*\n")
                append("🎯 Лидов найдено: *${result.matchedLeads}*\n")
                if (result.skippedLeads > 0) {
                    append("⏭ Пропущено (дубли): *${result.skippedLeads}*\n")
                }
                append("\n")
                if (result.matchedLeads > 0) {
                    append("Лиды добавлены в ваш список и ожидают обработки.")
                } else {
                    append("Совпадений с ключевыми словами не найдено.\n")
                    append("Попробуйте добавить ключевые слова или проверьте файл экспорта.")
                }
            }

            if (savedMsgId != 0) {
                sender.editText(
                    chatId, savedMsgId,
                    successText,
                    keyboard(
                        row(btn("📋 Перейти к лидам", "menu:leads")),
                        row(btn("📤 Импортировать ещё", "export:start")),
                        row(btn("◀️ Назад к чатам", "menu:chats")),
                    ),
                    parseMarkdown = true,
                )
            } else {
                sender.sendText(chatId, successText, parseMarkdown = true)
            }
        }.onFailure { e ->
            log.warn(
                "[BOT][EXPORT] ❌ Ошибка импорта: userId=${user.id} email=${user.email} " +
                        "filename=\"$filename\" причина=\"${e.message}\""
            )

            val errText = "❌ *Ошибка импорта*\n\n${e.message ?: "Неизвестная ошибка."}\n\n" +
                    "Убедитесь, что файл экспортирован из *Telegram Desktop* в формате JSON или HTML."

            if (savedMsgId != 0) {
                sender.editText(
                    chatId, savedMsgId,
                    errText,
                    keyboard(
                        row(btn("🔄 Попробовать снова", "export:start")),
                        row(btn("◀️ Назад к чатам", "menu:chats")),
                    ),
                    parseMarkdown = true,
                )
            } else {
                sender.sendText(chatId, errText, parseMarkdown = true)
            }
        }
    }

    // ─── Утилиты ─────────────────────────────────────────────────────────────

    /**
     * Скачивает файл из Telegram через Bot API.
     * getFile() возвращает путь на серверах Telegram, после чего
     * делаем обычный HTTP GET на https://api.telegram.org/file/bot<TOKEN>/<path>.
     */
    private fun downloadFile(fileId: String): ByteArray {
        val tgFile = telegramClient.execute(GetFile(fileId))
            ?: throw IllegalStateException("Telegram не вернул информацию о файле")

        val filePath = tgFile.filePath
            ?: throw IllegalStateException("Путь к файлу отсутствует в ответе Telegram")

        val url = "https://api.telegram.org/file/bot$botToken/$filePath"

        return URI.create(url).toURL().openStream().use { it.readBytes() }
    }
}