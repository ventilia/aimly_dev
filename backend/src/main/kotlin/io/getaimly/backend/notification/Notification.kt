package io.getaimly.backend.notification

import io.getaimly.backend.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

enum class NotificationTarget { WEB, BOT, BOTH }

@Entity
@Table(name = "notifications")
class Notification(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var body: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var target: NotificationTarget = NotificationTarget.BOTH,

    @Column(name = "scheduled_at", nullable = false)
    var scheduledAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var sent: Boolean = false,

    @Column(name = "sent_at")
    var sentAt: LocalDateTime? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    var createdBy: User,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

@Entity
@Table(name = "user_notifications")
class UserNotification(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    val notification: Notification,

    @Column(nullable = false)
    var read: Boolean = false,

    @Column(name = "read_at")
    var readAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)