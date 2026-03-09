package io.getaimly.backend.notification

import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.user.UserRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime

data class CreateNotificationRequest(
    val title: String,
    val body: String,
    val target: NotificationTarget = NotificationTarget.BOTH,
    val scheduledAt: LocalDateTime = LocalDateTime.now(),
)

data class NotificationDto(
    val id: Long,
    val title: String,
    val body: String,
    val target: String,
    val scheduledAt: LocalDateTime,
    val sent: Boolean,
    val createdAt: LocalDateTime,
)

data class UserNotificationDto(
    val id: Long,
    val notificationId: Long,
    val title: String,
    val body: String,
    val read: Boolean,
    val createdAt: LocalDateTime,
)

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userNotificationRepository: UserNotificationRepository,
    private val userRepository: UserRepository,
    private val aimlyBot: AimlyBot,
) {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    @Transactional
    fun createNotification(request: CreateNotificationRequest, adminId: Long): NotificationDto {
        val admin = userRepository.findById(adminId)
            .orElseThrow { IllegalArgumentException("Администратор не найден") }

        val notification = notificationRepository.save(Notification(
            title       = request.title,
            body        = request.body,
            target      = request.target,
            scheduledAt = request.scheduledAt,
            createdBy   = admin,
        ))


        if (!notification.scheduledAt.isAfter(LocalDateTime.now())) {
            dispatchNotification(notification)
        }

        log.info("создано уведомление id=${notification.id} target=${notification.target} scheduledAt=${notification.scheduledAt}")
        return toDto(notification)
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    fun processPendingNotifications() {
        val pending = notificationRepository.findPendingToSend(LocalDateTime.now())
        if (pending.isEmpty()) return
        log.info("найдено ${pending.size} уведомлений к отправке")
        pending.forEach { dispatchNotification(it) }
    }

    @Transactional
    fun dispatchNotification(notification: Notification) {
        val users = userRepository.findAll()


        if (notification.target == NotificationTarget.WEB || notification.target == NotificationTarget.BOTH) {
            users.forEach { user ->
                val existing = userNotificationRepository
                    .findByUserIdAndNotificationId(user.id, notification.id)
                if (existing == null) {
                    userNotificationRepository.save(
                        UserNotification(user = user, notification = notification)
                    )
                }
            }
            log.info("созданы веб-уведомления для ${users.size} пользователей, notificationId=${notification.id}")
        }

        // Telegram-уведомления
        if (notification.target == NotificationTarget.BOT || notification.target == NotificationTarget.BOTH) {
            users.filter { it.telegramId != null }.forEach { user ->
                try {
                    aimlyBot.sendText(user.telegramId!!, "🔔 ${notification.title}\n\n${notification.body}")
                } catch (e: Exception) {
                    log.warn("не удалось отправить TG-уведомление userId=${user.id}: ${e.message}")
                }
            }
        }

        notification.sent   = true
        notification.sentAt = LocalDateTime.now()
        notificationRepository.save(notification)

        log.info("уведомление id=${notification.id} отправлено")
    }

    fun getNotificationsForUser(userId: Long): List<UserNotificationDto> {
        return userNotificationRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .map { un ->
                UserNotificationDto(
                    id             = un.id,             // id UserNotification — используется в markOneRead
                    notificationId = un.notification.id,
                    title          = un.notification.title,
                    body           = un.notification.body,
                    read           = un.read,
                    createdAt      = un.createdAt,
                )
            }
    }

    fun getUnreadCount(userId: Long): Long =
        userNotificationRepository.countByUserIdAndReadFalse(userId)

    @Transactional
    fun markAllRead(userId: Long) {
        userNotificationRepository.markAllReadForUser(userId, LocalDateTime.now())
    }


    @Transactional
    fun markOneRead(userNotificationId: Long, userId: Long) {
        val un = userNotificationRepository.findById(userNotificationId).orElse(null) ?: return
        if (un.user.id != userId) {
            log.warn("попытка прочитать чужое уведомление: userId=$userId, un.userId=${un.user.id}")
            return
        }
        if (!un.read) {
            un.read   = true
            un.readAt = LocalDateTime.now()
            userNotificationRepository.save(un)
        }
    }

    fun getAllNotifications(): List<NotificationDto> =
        notificationRepository.findAllOrderedByCreated().map { toDto(it) }

    private fun toDto(n: Notification) = NotificationDto(
        id          = n.id,
        title       = n.title,
        body        = n.body,
        target      = n.target.name,
        scheduledAt = n.scheduledAt,
        sent        = n.sent,
        createdAt   = n.createdAt,
    )
}