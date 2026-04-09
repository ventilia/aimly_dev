package io.getaimly.backend.lead

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.time.LocalDateTime

data class IncomingMessageRequest(

    val userId: Long,
    val tgMessageId: Long,
    val chatTgId: Long,
    val chatLink: String,
    val chatTitle: String,
    val authorName: String,
    val authorUsername: String,
    val messageText: String,
    val messageLink: String,
    val matchedKeyword: String,

    @JsonSetter(nulls = Nulls.SKIP)
    val contextMessages: List<String> = emptyList(),

    val isHistorical: Boolean = false,

    // --- НОВЫЕ ПОЛЯ ---

    // Источник лида. По умолчанию LIVE — бот поймал в реалтайме.
    // ChatExportService выставляет MANUAL_EXPORT.
    val source: LeadSource = LeadSource.LIVE,

    // Реальная дата сообщения (заполняется только для MANUAL_EXPORT).
    // Для LIVE равна null — в этом случае foundAt = LocalDateTime.now() и является датой сообщения.
    val messageDate: LocalDateTime? = null,
)