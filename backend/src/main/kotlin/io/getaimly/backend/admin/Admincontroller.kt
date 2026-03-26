package io.getaimly.backend.admin

import io.getaimly.backend.auth.BadRequestException
import io.getaimly.backend.auth.ForbiddenException
import io.getaimly.backend.auth.NotFoundException
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.lead.Lead
import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.lead.LeadStatus
import io.getaimly.backend.referral.ReferralActivationRepository
import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.Role
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

data class AdminUserDto(
    val id:                   Long,
    val email:                String,
    val firstName:            String?,
    val telegramId:           Long?,
    val telegramUsername:     String?,
    val emailVerified:        Boolean,
    val isActive:             Boolean,
    val role:                 String,
    val subscriptionStatus:   String?,
    val subscriptionPlan:     String?,
    val subscriptionExpiresAt: String?,
    val leadsCount:           Int,
    val chatCount:            Long,
    val keywordCount:         Int,
    val bonusDaysBuffer:      Int,
    val createdAt:            String?,
)

data class AdminLeadDto(
    val id:             Long,
    val chatTitle:      String,
    val chatLink:       String,
    val authorName:     String,
    val authorUsername: String,
    val messageText:    String,
    val messageLink:    String,
    val matchedKeyword: String,
    val status:         String,
    val foundAt:        String,
    val aiValid:        Boolean?,
    val aiReason:       String?,
    val userId:         Long?,
    val userEmail:      String?,
    val userFirstName:  String?,
)

data class AdminChatDto(
    val id:        Long,
    val chatLink:  String,
    val chatTitle: String,
    val isActive:  Boolean,
    val createdAt: String?,
)

data class AdminKeywordDto(
    val id:       Long,
    val keyword:  String,
    val isActive: Boolean,
)

data class AdminUserDetailDto(
    val id:               Long,
    val email:            String,
    val firstName:        String?,
    val telegramId:       Long?,
    val telegramUsername: String?,
    val emailVerified:    Boolean,
    val isActive:         Boolean,
    val role:             String,
    val createdAt:        String?,
    val subscriptionStatus:    String?,
    val subscriptionPlan:      String?,
    val subscriptionExpiresAt: String?,
    val bonusDaysBuffer:       Int,
    val trialUsed:             Boolean,
    val leadsCount:    Int,
    val newLeadsCount: Long,
    val chatCount:     Long,
    val keywordCount:  Int,
    val totalReferrals: Long,
    val paidReferrals:  Long,
    val businessContext: String?,
    val chats:       List<AdminChatDto>,
    val keywords:    List<AdminKeywordDto>,
    val recentLeads: List<AdminLeadDto>,
)

data class AdminLeadsPageDto(
    val content:       List<AdminLeadDto>,
    val totalElements: Long,
    val totalPages:    Int,
    val page:          Int,
    val size:          Int,
)

data class SetRoleRequest(val role: String)

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val userRepository:               UserRepository,
    private val leadRepository:               LeadRepository,
    private val subscriptionRepository:       ChatSubscriptionRepository,
    private val keywordRepository:            KeywordRepository,
    private val expiryRepository:             SubscriptionExpiryRepository,
    private val referralActivationRepository: ReferralActivationRepository,
) {
    private val log = LoggerFactory.getLogger(AdminController::class.java)

    private fun requireAdmin(user: User) {
        if (user.role != Role.ADMIN) throw ForbiddenException("доступ только для администраторов")
    }

    @GetMapping("/users")
    fun listUsers(@AuthenticationPrincipal user: User): ResponseEntity<List<AdminUserDto>> {
        requireAdmin(user)
        val users = userRepository.findAll().map { it.toListDto() }
        log.info("[ADMIN] Список пользователей: adminId=${user.id} всего=${users.size}")
        return ResponseEntity.ok(users)
    }

    @GetMapping("/users/{id}/detail")
    fun getUserDetail(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
    ): ResponseEntity<AdminUserDetailDto> {
        requireAdmin(user)
        val target = userRepository.findById(id).orElseThrow { NotFoundException("пользователь не найден") }
        val detail = buildDetailDto(target)
        log.info("[ADMIN] Детальный профиль: adminId=${user.id} targetId=$id email=${target.email}")
        return ResponseEntity.ok(detail)
    }

    @PostMapping("/users/{id}/role")
    fun setRole(
        @AuthenticationPrincipal user: User,
        @PathVariable id: Long,
        @RequestBody req: SetRoleRequest,
    ): ResponseEntity<AdminUserDto> {
        requireAdmin(user)
        if (user.id == id && req.role.uppercase() != "ADMIN") throw BadRequestException("нельзя снять роль ADMIN у самого себя")
        val target  = userRepository.findById(id).orElseThrow { NotFoundException("пользователь не найден") }
        val newRole = runCatching { Role.valueOf(req.role.uppercase()) }.getOrElse { throw IllegalArgumentException("неверная роль: ${req.role}") }
        target.role      = newRole
        target.updatedAt = LocalDateTime.now()
        userRepository.save(target)
        log.info("[ADMIN] Роль изменена: adminId=${user.id} targetId=$id newRole=$newRole")
        return ResponseEntity.ok(target.toListDto())
    }

    @GetMapping("/leads")
    fun getAllLeads(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "0")  page:    Int,
        @RequestParam(defaultValue = "50") size:    Int,
        @RequestParam(required = false)    userId:  Long?,
        @RequestParam(required = false)    status:  String?,
        @RequestParam(required = false)    keyword: String?,
    ): ResponseEntity<AdminLeadsPageDto> {
        requireAdmin(user)
        val pageRequest = PageRequest.of(page, size.coerceAtMost(200))
        val leadsPage   = leadRepository.findAllForAdmin(userId, status, keyword, pageRequest)
        log.info("[ADMIN] Лиды всех пользователей: adminId=${user.id} userId=$userId status=$status keyword=$keyword page=$page total=${leadsPage.totalElements}")
        return ResponseEntity.ok(
            AdminLeadsPageDto(
                content       = leadsPage.content.map { it.toAdminDto() },
                totalElements = leadsPage.totalElements,
                totalPages    = leadsPage.totalPages,
                page          = page,
                size          = size,
            )
        )
    }

    private fun User.toListDto(): AdminUserDto {
        val expiry    = expiryRepository.findByUserId(id)
        val chatCount = subscriptionRepository.countByUserIdAndIsActiveTrue(id)
        val kwCount   = keywordRepository.findByUserIdAndIsActiveTrue(id).size
        return AdminUserDto(
            id                    = id,
            email                 = email,
            firstName             = firstName,
            telegramId            = telegramId,
            telegramUsername      = telegramUsername,
            emailVerified         = emailVerified,
            isActive              = isActive,
            role                  = role.name,
            subscriptionStatus    = subscriptionStatus,
            subscriptionPlan      = subscriptionPlan,
            subscriptionExpiresAt = expiryRepository.findByUserId(id)?.expiresAt?.toString(),
            leadsCount            = leadsCount,
            chatCount             = chatCount,
            keywordCount          = kwCount,
            bonusDaysBuffer       = expiry?.bonusDaysBuffer ?: 0,
            createdAt             = createdAt?.toString(),
        )
    }

    private fun buildDetailDto(u: User): AdminUserDetailDto {
        val expiry     = expiryRepository.findByUserId(u.id)
        val chatCount  = subscriptionRepository.countByUserIdAndIsActiveTrue(u.id)
        val chats      = subscriptionRepository.findByUserIdAndIsActiveTrue(u.id)
        val keywords   = keywordRepository.findByUserIdAndIsActiveTrue(u.id)
        val newLeads   = leadRepository.countByUserIdAndStatus(u.id, LeadStatus.NEW)
        val totalRef   = referralActivationRepository.countByReferrerId(u.id)
        val paidRef    = referralActivationRepository.countByReferrerIdAndBonusGrantedTrue(u.id)
        val recentLeads = leadRepository
            .findByUserIdOrderByFoundAtDesc(u.id, PageRequest.of(0, 20))
            .content.map { it.toAdminDto() }

        return AdminUserDetailDto(
            id                    = u.id,
            email                 = u.email,
            firstName             = u.firstName,
            telegramId            = u.telegramId,
            telegramUsername      = u.telegramUsername,
            emailVerified         = u.emailVerified,
            isActive              = u.isActive,
            role                  = u.role.name,
            createdAt             = u.createdAt?.toString(),
            subscriptionStatus    = u.subscriptionStatus,
            subscriptionPlan      = u.subscriptionPlan,
            subscriptionExpiresAt = expiry?.expiresAt?.toString(),
            bonusDaysBuffer       = expiry?.bonusDaysBuffer ?: 0,
            trialUsed             = u.trialUsed,
            leadsCount            = u.leadsCount,
            newLeadsCount         = newLeads,
            chatCount             = chatCount,
            keywordCount          = keywords.size,
            totalReferrals        = totalRef,
            paidReferrals         = paidRef,
            businessContext       = u.businessContext,
            chats                 = chats.map { AdminChatDto(it.id, it.chatLink, it.chatTitle, it.isActive, it.createdAt.toString()) },
            keywords              = keywords.map { AdminKeywordDto(it.id, it.keyword, it.isActive) },
            recentLeads           = recentLeads,
        )
    }

    private fun Lead.toAdminDto(): AdminLeadDto {
        val sub = subscriptionId?.let { subscriptionRepository.findById(it).orElse(null) }
        return AdminLeadDto(
            id              = id,
            chatTitle       = sub?.chatTitle?.ifBlank { sub.chatLink } ?: "—",
            chatLink        = sub?.chatLink ?: "",
            authorName      = authorName,
            authorUsername  = authorUsername,
            messageText     = messageText,
            messageLink     = messageLink,
            matchedKeyword  = matchedKeyword,
            status          = status.name,
            foundAt         = foundAt.toString(),
            aiValid         = aiValid,
            aiReason        = aiReason,
            userId          = user.id,
            userEmail       = user.email,
            userFirstName   = user.firstName,
        )
    }
}