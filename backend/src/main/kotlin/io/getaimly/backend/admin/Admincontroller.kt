package io.getaimly.backend.admin

import io.getaimly.backend.auth.BadRequestException
import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.auth.NotFoundException
import io.getaimly.backend.user.Role
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

data class AdminUserDto(
    val id: Long,
    val email: String,
    val firstName: String?,
    val telegramId: Long?,
    val telegramUsername: String?,
    val emailVerified: Boolean,
    val isActive: Boolean,
    val role: String,
    val balance: Int,
    val subscriptionStatus: String?,
    val subscriptionPlan: String?,
    val leadsCount: Int,
    val createdAt: String?,
)

data class SetRoleRequest(val role: String)

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val userRepository: UserRepository,
) {

    private fun requireAdmin(user: User) {
        if (user.role != Role.ADMIN) throw ForbiddenException("доступ только для администраторов")
    }

    @GetMapping("/users")
    fun listUsers(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<List<AdminUserDto>> {
        requireAdmin(user)
        val users = userRepository.findAll().map { it.toDto() }
        return ResponseEntity.ok(users)
    }

    @GetMapping("/users/{id}")
    fun getUser(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
    ): ResponseEntity<AdminUserDto> {
        requireAdmin(user)
        val target = userRepository.findById(id)
            .orElseThrow { NotFoundException("пользователь не найден") }
        return ResponseEntity.ok(target.toDto())
    }

    @PostMapping("/users/{id}/role")
    fun setRole(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
        @RequestBody req: SetRoleRequest,
    ): ResponseEntity<AdminUserDto> {
        requireAdmin(user)


        if (user.id == id && req.role.uppercase() != "ADMIN") {
            throw BadRequestException("нельзя снять роль ADMIN у самого себя")
        }

        val target = userRepository.findById(id)
            .orElseThrow { NotFoundException("пользователь не найден") }

        val newRole = runCatching { Role.valueOf(req.role.uppercase()) }
            .getOrElse { throw IllegalArgumentException("неверная роль: ${req.role}") }

        target.role      = newRole
        target.updatedAt = LocalDateTime.now()
        userRepository.save(target)

        return ResponseEntity.ok(target.toDto())
    }

    private fun User.toDto() = AdminUserDto(
        id                 = id,
        email              = email,
        firstName          = firstName,
        telegramId         = telegramId,
        telegramUsername   = telegramUsername,
        emailVerified      = emailVerified,
        isActive           = isActive,
        role               = role.name,
        balance            = balance,
        subscriptionStatus = subscriptionStatus,
        subscriptionPlan   = subscriptionPlan,
        leadsCount         = leadsCount,
        createdAt          = createdAt?.toString(),
    )
}