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
     * (tgNotifiedAt IS NOT NULL), но ещё не получил оценку (userRating IS NULL).
     *
     * Использует прямую проверку поля userRating на лиде — без JOIN к lead_feedbacks.
     */
    @Query("""
        SELECT l.id FROM Lead l
        WHERE l.user.id = :userId
          AND l.tgNotifiedAt IS NOT NULL
          AND l.userRating IS NULL
        ORDER BY l.tgNotifiedAt DESC
    """)
    fun findLatestNotifiedWithoutRating(
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): List<Long>

    /**
     * Устаревший вариант — оставлен для совместимости с BotLeadsHandler,
     * который ещё использует старое имя метода.
     * Делегирует к findLatestNotifiedWithoutRating.
     */
    @Query("""
        SELECT l.id FROM Lead l
        WHERE l.user.id = :userId
          AND l.tgNotifiedAt IS NOT NULL
          AND l.userRating IS NULL
        ORDER BY l.tgNotifiedAt DESC
    """)
    fun findLatestNotifiedWithoutFeedback(
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): List<Long>

    /**
     * Количество лидов в очереди pending_lead_notifications для данного пользователя.
     * Используется в UI бота для отображения размера очереди рядом с кнопками оценки.
     */
    @Query("""
        SELECT COUNT(p) FROM PendingLeadNotification p
        WHERE p.user.id = :userId
    """)
    fun countPendingNotificationsByUserId(@Param("userId") userId: Long): Long

    // ─── AI-промпт: оценённые лиды пользователя ──────────────────────────────

    /**
     * Общее количество оценённых лидов пользователя.
     * Используется для порога MIN_FEEDBACKS_FOR_PROMPT.
     */
    @Query("SELECT COUNT(l) FROM Lead l WHERE l.user.id = :userId AND l.userRating IS NOT NULL")
    fun countByUserIdAndUserRatingNotNull(@Param("userId") userId: Long): Long

    /**
     * Последние оценённые лиды по конкретному ключевому слову.
     * Наиболее релевантные примеры для AI-промпта.
     */
    @Query("""
        SELECT l FROM Lead l
        WHERE l.user.id = :userId
          AND l.matchedKeyword = :keyword
          AND l.userRating IS NOT NULL
        ORDER BY l.ratingAt DESC
    """)
    fun findRatedByUserIdAndKeyword(
        @Param("userId")  userId:  Long,
        @Param("keyword") keyword: String,
        pageable: Pageable,
    ): List<Lead>

    /**
     * Последние оценённые лиды пользователя — общая выборка для контекста AI-промпта.
     */
    @Query("""
        SELECT l FROM Lead l
        WHERE l.user.id = :userId
          AND l.userRating IS NOT NULL
        ORDER BY l.ratingAt DESC
    """)
    fun findRecentRatedByUserId(
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): List<Lead>
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