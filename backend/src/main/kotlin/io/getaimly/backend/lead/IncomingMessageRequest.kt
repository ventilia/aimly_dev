package io.getaimly.backend.lead

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

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
)