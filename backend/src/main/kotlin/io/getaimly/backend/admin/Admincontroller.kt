package io.getaimly.backend.admin

import io.getaimly.backend.auth.BadRequestException
import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.auth.NotFoundException
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.lead.LeadRating
import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.lead.LeadStatus
import io.getaimly.backend.user.Role
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

// ─── Базовый DTO пользователя (список) ───────────────────────────────────────

data class AdminUserDto(
    val id: Long,
    val email: String,
    val firstName: String?,
    val telegramId: Long?,
    val telegramUsername: String?,
    val emailVerified: Boolean,
    val isActive: Boolean,
    val role: String,
    val subscriptionStatus: String?,
    val subscriptionPlan: String?,
    val leadsCount: Int,
    val createdAt: String?,
)

// ─── Детальный DTO пользователя ───────────────────────────────────────────────

data class AdminUserDetailDto(
    val id: Long,
    val email: String,
    val firstName: String?,
    val telegramId: Long?,
    val telegramUsername: String?,
    val telegramLinkedAt: String?,
    val emailVerified: Boolean,
    val isActive: Boolean,
    val role: String,
    val subscriptionStatus: String?,
    val subscriptionPlan: String?,
    val leadsCount: Int,
    val createdAt: String?,
    val updatedAt: String?,
    // AI-профиль
    val businessContext: String?,
    val respondToServiceOffers: Boolean,
    // Последний поиск чатов
    val lastChatSearchQuery: String?,
    val lastChatSearchAt: String?,
    val lastChatSearchPeerType: String?,
    val lastChatSearchQueriesJson: String?,
    val lastChatSearchResultsJson: String?,
    // Чаты и ключевые слова
    val chats: List<AdminChatDto>,
    val keywords: List<AdminKeywordDto>,
    // Последние лиды (до 10)
    val recentLeads: List<AdminLeadDto>,
    // Последние оценённые лиды (до 25) — для секции «Оценки для AI»
    val ratedLeads: List<AdminLeadDto>,
    // Статистика лидов
    val leadsNew: Long,
    val leadsViewed: Long,
    val leadsReplied: Long,
    val leadsIgnored: Long,
)

data class AdminChatDto(
    val id: Long,
    val chatLink: String,
    val chatTitle: String,
    val isActive: Boolean,
    val createdAt: String,
)

data class AdminKeywordDto(
    val id: Long,
    val keyword: String,
    val variants: List<String>,
    val isActive: Boolean,
)

data class AdminLeadDto(
    val id: Long,
    val userId: Long,
    val userEmail: String,
    val chatTitle: String,
    val chatLink: String,
    val authorName: String,
    val authorUsername: String,
    val messageText: String,
    val messageLink: String,
    val matchedKeyword: String,
    val status: String,
    val aiValid: Boolean?,
    val aiReason: String?,
    val foundAt: String,
    // Оценка пользователя — null если не оценён
    val userRating: String?,
    val ratingAt: String?,
)

// ─── DTO для страницы всех лидов ─────────────────────────────────────────────

data class AdminLeadPageDto(
    val content: List<AdminLeadDto>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int,
)

data class SetRoleRequest(val role: String)

data class SetLeadRatingRequest(
    // "GOOD", "BAD" или null для сброса оценки
    val rating: String?,
)

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val userRepository: UserRepository,
    private val leadRepository: LeadRepository,
    private val subscriptionRepository: ChatSubscriptionRepository,
    private val keywordRepository: KeywordRepository,
) {

    private fun requireAdmin(user: User) {
        if (user.role != Role.ADMIN) throw ForbiddenException("доступ только для администраторов")
    }

    // ─── Список пользователей ─────────────────────────────────────────────────

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

    // ─── Детали пользователя ──────────────────────────────────────────────────

    @GetMapping("/users/{id}/details")
    fun getUserDetails(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
    ): ResponseEntity<AdminUserDetailDto> {
        requireAdmin(user)

        val target = userRepository.findById(id)
            .orElseThrow { NotFoundException("пользователь не найден") }

        val chats = subscriptionRepository.findByUserId(target.id).map { sub ->
            AdminChatDto(
                id        = sub.id,
                chatLink  = sub.chatLink,
                chatTitle = sub.chatTitle.ifBlank { sub.chatLink },
                isActive  = sub.isActive,
                createdAt = sub.createdAt.toString(),
            )
        }

        val keywords = keywordRepository.findByUserId(target.id).map { kw ->
            AdminKeywordDto(
                id       = kw.id,
                keyword  = kw.keyword,
                variants = kw.variants
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                isActive = kw.isActive,
            )
        }

        val recentLeads = leadRepository.findByUserIdOrderByFoundAtDesc(
            userId   = target.id,
            pageable = PageRequest.of(0, 10),
        ).content.map { it.toAdminDto(target.email) }

        // Последние 25 оценённых лидов для секции «Оценки для AI»
        val ratedLeads = leadRepository.findRecentRatedByUserId(
            userId   = target.id,
            pageable = PageRequest.of(0, 25),
        ).map { it.toAdminDto(target.email) }

        val leadsNew     = leadRepository.countByUserIdAndStatus(target.id, LeadStatus.NEW)
        val leadsViewed  = leadRepository.countByUserIdAndStatus(target.id, LeadStatus.VIEWED)
        val leadsReplied = leadRepository.countByUserIdAndStatus(target.id, LeadStatus.REPLIED)
        val leadsIgnored = leadRepository.countByUserIdAndStatus(target.id, LeadStatus.IGNORED)

        val detail = AdminUserDetailDto(
            id                      = target.id,
            email                   = target.email,
            firstName               = target.firstName,
            telegramId              = target.telegramId,
            telegramUsername        = target.telegramUsername,
            telegramLinkedAt        = target.telegramLinkedAt?.toString(),
            emailVerified           = target.emailVerified,
            isActive                = target.isActive,
            role                    = target.role.name,
            subscriptionStatus      = target.subscriptionStatus,
            subscriptionPlan        = target.subscriptionPlan,
            leadsCount              = target.leadsCount,
            createdAt               = target.createdAt?.toString(),
            updatedAt               = target.updatedAt?.toString(),
            businessContext         = target.businessContext,
            respondToServiceOffers  = target.respondToServiceOffers,
            lastChatSearchQuery     = target.lastChatSearchQuery,
            lastChatSearchAt        = target.lastChatSearchAt?.toString(),
            lastChatSearchPeerType  = target.lastChatSearchPeerType,
            lastChatSearchQueriesJson = target.lastChatSearchQueriesJson,
            lastChatSearchResultsJson = target.lastChatSearchResultsJson,
            chats                   = chats,
            keywords                = keywords,
            recentLeads             = recentLeads,
            ratedLeads              = ratedLeads,
            leadsNew                = leadsNew,
            leadsViewed             = leadsViewed,
            leadsReplied            = leadsReplied,
            leadsIgnored            = leadsIgnored,
        )

        return ResponseEntity.ok(detail)
    }

    // ─── Все лиды всех пользователей ─────────────────────────────────────────

    @GetMapping("/leads")
    fun getAllLeads(
        @AuthenticationPrincipal user: User,
        @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<AdminLeadPageDto> {
        requireAdmin(user)

        val safeSize = size.coerceIn(1, 100)
        val pageable = PageRequest.of(page, safeSize, Sort.by(Sort.Direction.DESC, "foundAt"))

        val result = when {
            userId != null && status != null -> {
                val s = runCatching { LeadStatus.valueOf(status.uppercase()) }
                    .getOrElse { throw BadRequestException("неверный статус: $status") }
                leadRepository.findByUserIdAndStatusOrderByFoundAtDesc(userId, s, pageable)
            }
            userId != null -> {
                leadRepository.findByUserIdOrderByFoundAtDesc(userId, pageable)
            }
            status != null -> {
                val s = runCatching { LeadStatus.valueOf(status.uppercase()) }
                    .getOrElse { throw BadRequestException("неверный статус: $status") }
                leadRepository.findByStatusOrderByFoundAtDesc(s, pageable)
            }
            else -> {
                leadRepository.findAllByOrderByFoundAtDesc(pageable)
            }
        }

        val userEmailCache = mutableMapOf<Long, String>()

        val content = result.content.map { lead ->
            val userEmail = userEmailCache.getOrPut(lead.user.id) {
                userRepository.findById(lead.user.id).map { it.email }.orElse("unknown")
            }
            lead.toAdminDto(userEmail)
        }

        return ResponseEntity.ok(
            AdminLeadPageDto(
                content       = content,
                totalElements = result.totalElements,
                totalPages    = result.totalPages,
                page          = result.number,
                size          = result.size,
            )
        )
    }

    // ─── Изменение оценки лида (только для Admin) ─────────────────────────────

    @PatchMapping("/leads/{id}/rating")
    fun setLeadRating(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
        @RequestBody req: SetLeadRatingRequest,
    ): ResponseEntity<AdminLeadDto> {
        requireAdmin(user)

        val lead = leadRepository.findById(id)
            .orElseThrow { NotFoundException("лид #$id не найден") }

        val newRating = when {
            req.rating == null -> null
            else -> runCatching { LeadRating.valueOf(req.rating.uppercase()) }
                .getOrElse { throw BadRequestException("неверная оценка: ${req.rating}. Допустимы: GOOD, BAD, null") }
        }

        lead.userRating = newRating
        lead.ratingAt   = if (newRating != null) LocalDateTime.now() else null
        leadRepository.save(lead)

        val userEmail = userRepository.findById(lead.user.id).map { it.email }.orElse("unknown")

        return ResponseEntity.ok(lead.toAdminDto(userEmail))
    }

    // ─── Изменение роли ───────────────────────────────────────────────────────

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

    // ─── Маппинг ──────────────────────────────────────────────────────────────

    private fun User.toDto() = AdminUserDto(
        id                 = id,
        email              = email,
        firstName          = firstName,
        telegramId         = telegramId,
        telegramUsername   = telegramUsername,
        emailVerified      = emailVerified,
        isActive           = isActive,
        role               = role.name,
        subscriptionStatus = subscriptionStatus,
        subscriptionPlan   = subscriptionPlan,
        leadsCount         = leadsCount,
        createdAt          = createdAt?.toString(),
    )

    private fun io.getaimly.backend.lead.Lead.toAdminDto(userEmail: String) = AdminLeadDto(
        id             = id,
        userId         = user.id,
        userEmail      = userEmail,
        chatTitle      = run {
            val sub = subscriptionId?.let { subscriptionRepository.findById(it).orElse(null) }
            sub?.chatTitle?.ifBlank { sub.chatLink } ?: ""
        },
        chatLink       = run {
            val sub = subscriptionId?.let { subscriptionRepository.findById(it).orElse(null) }
            sub?.chatLink ?: ""
        },
        authorName      = authorName,
        authorUsername  = authorUsername,
        messageText     = messageText,
        messageLink     = messageLink,
        matchedKeyword  = matchedKeyword,
        status          = status.name,
        aiValid         = aiValid,
        aiReason        = aiReason,
        foundAt         = foundAt.toString(),
        userRating      = userRating?.name,
        ratingAt        = ratingAt?.toString(),
    )
}