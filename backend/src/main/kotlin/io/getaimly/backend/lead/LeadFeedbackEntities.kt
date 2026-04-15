package io.getaimly.backend.lead

import io.getaimly.backend.user.User
import jakarta.persistence.*
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

enum class LeadRating { GOOD, BAD }

@Entity
@Table(
    name = "lead_feedbacks",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "lead_id"])]
)
class LeadFeedback(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    val lead: Lead,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val rating: LeadRating,

    @Column(name = "message_snippet", nullable = false, columnDefinition = "TEXT")
    val messageSnippet: String = "",

    @Column(name = "matched_keyword", nullable = false, length = 200)
    val matchedKeyword: String = "",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

@Entity
@Table(
    name = "pending_lead_notifications",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "lead_id"])]
)
class PendingLeadNotification(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    val lead: Lead,

    @Column(name = "chat_title", nullable = false, columnDefinition = "TEXT")
    val chatTitle: String = "",

    @Column(name = "message_preview", nullable = false, columnDefinition = "TEXT")
    val messagePreview: String = "",

    @Column(name = "message_link", nullable = false, length = 500)
    val messageLink: String = "",

    @Column(name = "keyword", nullable = false, length = 200)
    val keyword: String = "",

    @Column(name = "author_username", nullable = false, length = 200)
    val authorUsername: String = "",

    @Column(name = "author_name", nullable = false, length = 200)
    val authorName: String = "",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

@Repository
interface LeadFeedbackRepository : JpaRepository<LeadFeedback, Long> {

    fun existsByUserIdAndLeadId(userId: Long, leadId: Long): Boolean

    fun findByUserIdAndLeadId(userId: Long, leadId: Long): LeadFeedback?

    @Query("""
        SELECT f FROM LeadFeedback f
        WHERE f.user.id = :userId
        ORDER BY f.createdAt DESC
    """)
    fun findRecentByUserId(
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): List<LeadFeedback>

    @Query("""
        SELECT f FROM LeadFeedback f
        WHERE f.user.id = :userId
          AND f.matchedKeyword = :keyword
        ORDER BY f.createdAt DESC
    """)
    fun findRecentByUserIdAndKeyword(
        @Param("userId")  userId:  Long,
        @Param("keyword") keyword: String,
        pageable: Pageable,
    ): List<LeadFeedback>

    fun countByUserId(userId: Long): Long

    /**
     * Батч-загрузка оценок по списку лидов одним запросом.
     * Используется в LeadService.getLeads() чтобы избежать N+1.
     */
    @Query("""
        SELECT f FROM LeadFeedback f
        WHERE f.user.id = :userId
          AND f.lead.id IN :leadIds
    """)
    fun findByUserIdAndLeadIdIn(
        @Param("userId")  userId:  Long,
        @Param("leadIds") leadIds: List<Long>,
    ): List<LeadFeedback>
}

@Repository
interface PendingLeadNotificationRepository : JpaRepository<PendingLeadNotification, Long> {

    @Query("""
        SELECT p FROM PendingLeadNotification p
        WHERE p.user.id = :userId
        ORDER BY p.createdAt ASC
    """)
    fun findOldestByUserId(
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): List<PendingLeadNotification>

    fun countByUserId(userId: Long): Long

    fun existsByUserIdAndLeadId(userId: Long, leadId: Long): Boolean

    fun deleteByUserIdAndLeadId(userId: Long, leadId: Long)
}