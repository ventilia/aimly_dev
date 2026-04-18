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

// Сущность LeadFeedback и LeadFeedbackRepository удалены — оценки теперь хранятся
// прямо в полях Lead.userRating и Lead.ratingAt (см. V30__lead_rating_inline.sql).
// Таблица lead_feedbacks удалена миграцией V31__drop_lead_feedbacks.sql.

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

    // nudge_tg_message_id хранится здесь для удобства поиска,
    // но дублируется и в leads.nudge_tg_message_id — чтобы удалить сообщение
    // даже если pending-запись уже была удалена.
    @Column(name = "nudge_tg_message_id")
    var nudgeTgMessageId: Int? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

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

    fun findByUserIdAndLeadId(userId: Long, leadId: Long): PendingLeadNotification?
}