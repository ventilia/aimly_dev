package io.getaimly.backend.notification

import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.user.Role
import io.getaimly.backend.user.User
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) {

    @GetMapping("/unread-count")
    fun unreadCount(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<Map<String, Long>> {
        val count = notificationService.getUnreadCount(user.id)
        return ResponseEntity.ok(mapOf("unread" to count))
    }

    @GetMapping
    fun getAll(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<List<UserNotificationDto>> {
        return ResponseEntity.ok(notificationService.getNotificationsForUser(user.id))
    }

    @PostMapping("/read-all")
    fun markAllRead(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<Void> {
        notificationService.markAllRead(user.id)
        return ResponseEntity.ok().build()
    }

    // {id} здесь — это id записи UserNotification, не notification
    @PostMapping("/{id}/read")
    fun markOneRead(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        notificationService.markOneRead(id, user.id)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/admin")
    fun createNotification(
        @AuthenticationPrincipal user: User,
        @RequestBody request: CreateNotificationRequest,
    ): ResponseEntity<NotificationDto> {
        if (user.role != Role.ADMIN) throw ForbiddenException("Доступ только для администраторов")
        return ResponseEntity.ok(notificationService.createNotification(request, user.id))
    }

    @GetMapping("/admin")
    fun getAllAdmin(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<List<NotificationDto>> {
        if (user.role != Role.ADMIN) throw ForbiddenException("Доступ только для администраторов")
        return ResponseEntity.ok(notificationService.getAllNotifications())
    }
}