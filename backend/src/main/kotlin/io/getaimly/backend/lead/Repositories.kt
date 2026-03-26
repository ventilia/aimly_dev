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

    // ── Для админ-панели: все лиды всех пользователей с фильтрацией ──────────

    @Query("""
        SELECT l FROM Lead l
        JOIN FETCH l.user u
        WHERE (:userId IS NULL OR u.id = :userId)
          AND (:status IS NULL OR l.status = :status)
          AND (:keyword IS NULL OR lower(l.matchedKeyword) LIKE lower(concat('%', :keyword, '%')))
        ORDER BY l.foundAt DESC
    """)
    fun findAllForAdmin(
        @Param("userId")  userId:  Long?,
        @Param("status")  status:  String?,
        @Param("keyword") keyword: String?,
        pageable: Pageable,
    ): Page<Lead>

    // ── Для детальной карточки пользователя: последние N лидов ───────────────
    // (findByUserIdOrderByFoundAtDesc уже есть — используется с PageRequest.of(0, 20))
}

@Repository
interface ChatSubscriptionRepository : JpaRepository<ChatSubscription, Long> {

    fun findByUserIdAndIsActiveTrue(userId: Long): List<ChatSubscription>

    fun findByUserIdAndChatLink(userId: Long, chatLink: String): ChatSubscription?

    fun findByUserIdAndChatLinkAndIsActiveTrue(userId: Long, chatLink: String): ChatSubscription?

    fun countByUserIdAndIsActiveTrue(userId: Long): Long

    @Query("SELECT cs FROM ChatSubscription cs WHERE cs.chatTgId = :chatTgId AND cs.isActive = true")
    fun findByChatTgId(@Param("chatTgId") chatTgId: Long): List<ChatSubscription>
}

@Repository
interface KeywordRepository : JpaRepository<Keyword, Long> {

    fun findByUserIdAndIsActiveTrue(userId: Long): List<Keyword>

    fun findByUserIdAndKeywordAndIsActiveTrue(userId: Long, keyword: String): Keyword?

    fun findByUserIdAndKeyword(userId: Long, keyword: String): Keyword?

    @Modifying
    @Query("UPDATE Keyword k SET k.isActive = false WHERE k.user.id = :userId")
    fun deactivateAllByUserId(@Param("userId") userId: Long)
}