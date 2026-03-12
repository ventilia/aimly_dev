package io.getaimly.backend.bot

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.generics.TelegramClient

class BotSender(private val client: TelegramClient) {

    private val log = LoggerFactory.getLogger(BotSender::class.java)

    fun sendText(
        chatId: Long,
        text: String,
        markup: InlineKeyboardMarkup? = null,
        parseMarkdown: Boolean = false,
    ) {
        val b = SendMessage.builder()
            .chatId(chatId.toString())
            .text(text)
        markup?.let { b.replyMarkup(it) }
        if (parseMarkdown) b.parseMode("Markdown")
        runCatching { client.execute(b.build()) }
            .onFailure { log.warn("sendText failed chatId=$chatId: ${it.message}") }
    }

    fun editText(
        chatId: Long,
        msgId: Int,
        text: String,
        markup: InlineKeyboardMarkup? = null,
        parseMarkdown: Boolean = false,
    ) {
        val b = EditMessageText.builder()
            .chatId(chatId.toString())
            .messageId(msgId)
            .text(text)
        markup?.let { b.replyMarkup(it) }
        if (parseMarkdown) b.parseMode("Markdown")
        runCatching { client.execute(b.build()) }
            .onFailure { log.debug("editText failed chatId=$chatId msgId=$msgId: ${it.message}") }
    }
}