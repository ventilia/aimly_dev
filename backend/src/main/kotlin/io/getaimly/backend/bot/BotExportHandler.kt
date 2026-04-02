package io.getaimly.backend.bot
import io.getaimly.backend.lead.ChatExportService
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.web.multipart.MultipartFile
import org.telegram.telegrambots.meta.api.methods.getfile.GetFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class BotExportHandler(
    private val sender: BotSender,
    private val sessions: ConcurrentHashMap<Long, UserSession>,
    private val userRepository: UserRepository,
    private val chatExportService: ChatExportService,
    private val telegramClient: TelegramClient,
    private val botToken: String,
) {
    private val log = LoggerFactory.getLogger(BotExportHandler::class.java)

    fun startExport(chatId: Long, msgId: Int) {
        sessions[chatId] = UserSession(
            step = BotStep.WAITING_EXPORT_FILE,
            exportMsgId = msgId,
        )
        sender.editText(
            chatId, msgId,
            "📥 *Анализ экспорта чата*\n\n" +
            "Отправьте файл экспорта из Telegram Desktop.\n\n" +
            "*Как получить экспорт:*\n" +
            "1️⃣ Откройте Telegram Desktop\n" +
            "2️⃣ Перейдите в нужный чат\n" +
            "3️⃣ Нажмите ⋮ (три точки) → *Экспортировать историю чата*\n" +
            "4️⃣ Выберите формат *HTML* или *JSON*\n" +
            "5️⃣ Дождитесь сохранения и отправьте файл сюда\n\n" +
            "⚠️ Максимальный размер: 100 МБ",
            markup = keyboard(
                row(btn("❌ Отмена", "chat:export:cancel"))
            ),
            parseMarkdown = true
        )
    }

    fun handleDocument(chatId: Long, message: Message, from: User) {
        val session = sessions[chatId]
        if (session?.step != BotStep.WAITING_EXPORT_FILE) return

        val document = message.document ?: return
        val fileId = document.fileId
        val fileName = document.fileName ?: "export.bin"
        val tgUser = "${from.firstName} (@${from.userName ?: "—"}, tgId=${from.id})"

        log.info("[EXPORT] Получен файл: $tgUser file=$fileName size=${document.fileSize}")

        if (document.fileSize > 100L * 1024 * 1024) {
            sender.editText(chatId, session.exportMsgId, "⚠️ Файл слишком большой. Максимальный размер: 100 МБ.", parseMarkdown = true)
            return
        }

        val user = userRepository.findByTelegramId(from.id).orElse(null)
        if (user == null) {
            sender.editText(chatId, session.exportMsgId, "❌ Ошибка авторизации. Используйте /start.", parseMarkdown = true)
            return
        }

        try {
            // Скачиваем файл с серверов Telegram
            val fileObj = telegramClient.execute(GetFile().fileId(fileId))
            val filePath = fileObj.filePath
            val url = "https://api.telegram.org/file/bot$botToken/$filePath"
            val bytes = downloadFile(url)

            val multipartFile: MultipartFile = ByteArrayMultipartFile(
                bytes = bytes,
                name = "file",
                originalFilename = fileName,
                contentType = document.mimeType
            )

            sender.editText(chatId, session.exportMsgId, "⏳ Обрабатываю экспорт и ищу лиды...", parseMarkdown = true)

            val result = chatExportService.processExport(user, multipartFile)

            val responseText = buildString {
                append("✅ *Экспорт успешно обработан!*\n\n")
                append("📁 Чат: `${result.chatTitle}`\n")
                append("📊 Всего сообщений: `${result.totalMessages}`\n")
                append("🎯 Найдено лидов: `${result.matchedLeads}`\n")
                if (result.skippedLeads > 0) append("⏭ Пропущено (дубли/нет ключевых слов): `${result.skippedLeads}`\n")
                append("\n📬 Новые лиды уже доступны в разделе `/leads`")
            }

            sender.editText(
                chatId, session.exportMsgId,
                responseText,
                markup = keyboard(
                    row(btn("📋 К чатам", "menu:chats")),
                    row(btn("🔄 Загрузить ещё", "chat:export"))
                ),
                parseMarkdown = true
            )
            sessions.remove(chatId)
        } catch (e: IllegalArgumentException) {
            // Валидация формата/размера/пустоты ключевых слов из ChatExportService
            sender.editText(chatId, session.exportMsgId, "❌ ${e.message}", parseMarkdown = true)
        } catch (e: Exception) {
            log.error("[EXPORT] Критическая ошибка обработки: $tgUser", e)
            sender.editText(chatId, session.exportMsgId, "❌ Ошибка обработки файла. Попробуйте ещё раз.", parseMarkdown = true)
        }
    }

    fun cancelExport(chatId: Long, msgId: Int) {
        sessions.remove(chatId)
        sender.editText(chatId, msgId, "Отменено. Нажмите /start чтобы вернуться в меню.", parseMarkdown = true)
    }

    private fun downloadFile(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        return try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}