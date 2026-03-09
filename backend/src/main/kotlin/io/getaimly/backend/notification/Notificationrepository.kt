package io.getaimly.backend.notification

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface NotificationRepository : JpaRepository<Notification, Long> {

    @Query("SELECT n FROM Notification n WHERE n.sent = false AND n.scheduledAt <= :now ORDER BY n.scheduledAt")
    fun findPendingToSend(now: LocalDateTime): List<Notification>

    @Query("SELECT n FROM Notification n ORDER BY n.createdAt DESC")
    fun findAllOrderedByCreated(): List<Notification>
}

interface UserNotificationRepository : JpaRepository<UserNotification, Long> {

    fun countByUserIdAndReadFalse(userId: Long): Long

    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<UserNotification>

    @Modifying
    @Query("UPDATE UserNotification un SET un.read = true, un.readAt = :now WHERE un.user.id = :userId AND un.read = false")
    fun markAllReadForUser(userId: Long, now: LocalDateTime)

    fun findByUserIdAndNotificationId(userId: Long, notificationId: Long): UserNotification?
}