package io.getaimly.backend.lead

import io.getaimly.backend.user.User
import jakarta.persistence.*
import java.time.LocalDateTime


enum class LeadStatus { NEW, VIEWED, REPLIED, IGNORED }

// Источник лида: LIVE — найден ботом в реальном времени, MANUAL_EXPORT — из ручного экспорта файла
enum class LeadSource { LIVE, MANUAL_EXPORT }


@Entity
@Table(
    name = "chat_subscriptions",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "chat_link"])]
)
class ChatSubscription(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "chat_link", nullable = false)
    val chatLink: String,

    @Column(name = "chat_title", nullable = false)
    var chatTitle: String = "",

    @Column(name = "chat_tg_id", nullable = false)
    var chatTgId: Long = 0,

    @Column(name = "session_id")
    var sessionId: Long? = null,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)



@Entity
@Table(
    name = "keywords",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "keyword"])]
)
class Keyword(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "keyword", nullable = false)
    val keyword: String,

    @Column(name = "variants", nullable = true, columnDefinition = "TEXT")
    var variants: String? = null,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {

    fun allVariants(): List<String> {
        val fromVariants = variants
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return (listOf(keyword) + fromVariants).distinct()
    }
}



@Entity
@Table(
    name = "leads",
    uniqueConstraints = [UniqueConstraint(columnNames = ["tg_message_id", "tg_chat_id", "user_id"])]
)
class Lead(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "subscription_id")
    val subscriptionId: Long? = null,

    @Column(name = "tg_message_id", nullable = false)
    val tgMessageId: Long,

    @Column(name = "tg_chat_id", nullable = false)
    val tgChatId: Long,

    @Column(name = "author_name", nullable = false)
    val authorName: String = "",

    @Column(name = "author_username", nullable = false)
    val authorUsername: String = "",

    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    val messageText: String,

    @Column(name = "message_link", nullable = false, length = 500)
    val messageLink: String = "",

    @Column(name = "matched_keyword", nullable = false)
    val matchedKeyword: String = "",

    @Column(name = "ai_valid")
    var aiValid: Boolean? = null,

    @Column(name = "ai_reason", columnDefinition = "TEXT")
    var aiReason: String? = null,

    @Column(name = "context_messages", columnDefinition = "TEXT")
    var contextMessages: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: LeadStatus = LeadStatus.NEW,

    @Column(name = "found_at", nullable = false)
    val foundAt: LocalDateTime = LocalDateTime.now(),

    // --- НОВЫЕ ПОЛЯ ---

    // Источник лида: LIVE (бот поймал в реальном времени) или MANUAL_EXPORT (ручной импорт файла)
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    val source: LeadSource = LeadSource.LIVE,

    // Оригинальная дата сообщения из файла экспорта.
    // Для LIVE-лидов равна foundAt (момент обнаружения).
    // Для MANUAL_EXPORT — реальное время написания сообщения в Telegram.
    @Column(name = "message_date")
    val messageDate: LocalDateTime? = null,
)