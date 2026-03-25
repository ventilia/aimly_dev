package io.getaimly.backend.subscription

import io.getaimly.backend.user.User
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Entity
@Table(name = "subscription_expiry")
class SubscriptionExpiry(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    // Дата из последнего вебхука Tribute (или ручного выставления).
    // Перезаписывается при каждом renewed_subscription — буфер не трогает.
    @Column(name = "expires_at", nullable = false)
    var expiresAt: LocalDateTime,

    @Column(name = "notified_renewal", nullable = false)
    var notifiedRenewal: Boolean = false,

    // Накопленные бонусные дни (реферальная программа, акции и т.д.).
    // НЕ перезаписывается вебхуками Tribute.
    // Расходуется только в deactivateExpired() когда Tribute не продлил вовремя.
    @Column(name = "bonus_days_buffer", nullable = false)
    var bonusDaysBuffer: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

@Repository
interface SubscriptionExpiryRepository : JpaRepository<SubscriptionExpiry, Long> {

    fun findByUserId(userId: Long): SubscriptionExpiry?

    @Query("""
        SELECT se FROM SubscriptionExpiry se
        JOIN FETCH se.user u
        WHERE se.expiresAt <= :threshold
          AND se.notifiedRenewal = false
          AND u.subscriptionStatus IN ('ACTIVE', 'TRIAL')
    """)
    fun findExpiringAndNotNotified(@Param("threshold") threshold: LocalDateTime): List<SubscriptionExpiry>

    @Query("""
        SELECT se FROM SubscriptionExpiry se
        JOIN FETCH se.user u
        WHERE se.expiresAt < :now
          AND u.subscriptionStatus IN ('ACTIVE', 'TRIAL')
    """)
    fun findExpired(@Param("now") now: LocalDateTime): List<SubscriptionExpiry>
}