package io.getaimly.backend.lead


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
    val contextMessages: List<String> = emptyList(),
)