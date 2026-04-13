package io.getaimly.backend.lead

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface LeadRepository : JpaRepository<Lead, Long> {

    fun findByUserIdOrderByFoundAtDesc(userId: Long, pageable: Pageable): Page<Lead>

    fun findByUserIdAndStatusOrderByFoundAtDesc(userId: Long, status: LeadStatus, pageable: Pageable): Page<Lead>

    fun countByUserIdAndStatus(userId: Long, status: LeadStatus): Long

    fun countByUserId(userId: Long): Long

    fun existsByTgMessageIdAndTgChatIdAndUserId(tgMessageId: Long, tgChatId: Long, userId: Long): Boolean

    // ─── Для admin: все лиды без фильтра по userId ────────────────────────────

    fun findAllByOrderByFoundAtDesc(pageable: Pageable): Page<Lead>

    fun findByStatusOrderByFoundAtDesc(status: LeadStatus, pageable: Pageable): Page<Lead>

    // ─── Для admin: детали пользователя (последние лиды) ─────────────────────

    @Query("""
        SELECT l FROM Lead l
        WHERE l.user.id = :userId AND l.foundAt >= :since
        ORDER BY l.foundAt DESC
    """)
    fun findRecentByUserId(
        @Param("userId") userId: Long,
        @Param("since") since: LocalDateTime,
        pageable: Pageable,
    ): List<Lead>

    fun existsByUserIdAndAuthorUsernameAndFoundAtAfter(
        userId: Long,
        authorUsername: String,
        foundAt: LocalDateTime,
    ): Boolean

    @Modifying
    @Query("UPDATE Lead l SET l.status = io.getaimly.backend.lead.LeadStatus.VIEWED WHERE l.user.id = :userId AND l.status = io.getaimly.backend.lead.LeadStatus.NEW")
    fun markAllViewedByUserId(@Param("userId") userId: Long)

    // ─── Feedback-система: поиск неоцененных отправленных лидов ──────────────

    /**
     * Возвращает ID лида, который уже был отправлен пользователю в Telegram
     * (tgNotifiedAt IS NOT NULL), но ещё не получил оценку в lead_feedbacks.
     *
     * Использует NOT EXISTS вместо NOT IN, что:
     *  - корректно работает с пустым множеством оцененных лидов
     *  - не тянет список ID в память приложения
     *  - эффективнее на больших данных
     *
     * Вызывается из LeadFeedbackService.findUnratedNotifiedLeadId().
     */
    @Query("""
        SELECT l.id FROM Lead l
        WHERE l.user.id = :userId
          AND l.tgNotifiedAt IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM LeadFeedback f
              WHERE f.lead.id = l.id
                AND f.user.id = :userId
          )
        ORDER BY l.tgNotifiedAt DESC
    """)
    fun findLatestNotifiedWithoutFeedback(
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): List<Long>
}

@Repository
interface ChatSubscriptionRepository : JpaRepository<ChatSubscription, Long> {

    fun findByUserIdAndIsActiveTrue(userId: Long): List<ChatSubscription>

    // ─── Для admin: все подписки пользователя (включая неактивные) ───────────
    fun findByUserId(userId: Long): List<ChatSubscription>

    fun findByUserIdAndChatLink(userId: Long, chatLink: String): ChatSubscription?

    fun findByUserIdAndChatLinkAndIsActiveTrue(userId: Long, chatLink: String): ChatSubscription?

    fun countByUserIdAndIsActiveTrue(userId: Long): Long

    @Query("SELECT cs FROM ChatSubscription cs WHERE cs.chatTgId = :chatTgId AND cs.isActive = true")
    fun findByChatTgId(@Param("chatTgId") chatTgId: Long): List<ChatSubscription>
}

@Repository
interface KeywordRepository : JpaRepository<Keyword, Long> {

    fun findByUserIdAndIsActiveTrue(userId: Long): List<Keyword>

    fun findByUserId(userId: Long): List<Keyword>

    fun findByUserIdAndKeywordAndIsActiveTrue(userId: Long, keyword: String): Keyword?

    fun findByUserIdAndKeyword(userId: Long, keyword: String): Keyword?

    @Modifying
    @Query("UPDATE Keyword k SET k.isActive = false WHERE k.user.id = :userId")
    fun deactivateAllByUserId(@Param("userId") userId: Long)
}