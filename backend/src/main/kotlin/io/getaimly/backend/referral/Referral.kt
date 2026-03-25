package io.getaimly.backend.referral

import io.getaimly.backend.user.User
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

// ─── ReferralCode ─────────────────────────────────────────────────────────────
// Один уникальный код на пользователя. Генерируется один раз при первом запросе.

@Entity
@Table(
    name = "referral_codes",
    indexes = [Index(name = "idx_referral_code_code", columnList = "code", unique = true)],
)
class ReferralCode(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    // Короткий буквенно-цифровой код, например "A3X9KP"
    @Column(name = "code", nullable = false, unique = true, length = 16)
    val code: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

@Repository
interface ReferralCodeRepository : JpaRepository<ReferralCode, Long> {
    fun findByUserId(userId: Long): ReferralCode?
    fun findByCode(code: String): ReferralCode?
    fun existsByCode(code: String): Boolean
}


// ─── ReferralActivation ───────────────────────────────────────────────────────
// Фиксирует факт перехода по реферальной ссылке + факт начисления бонуса.
// referrerId — тот, чья ссылка; refereeId — тот, кто пришёл по ссылке.
// bonusGranted становится true ровно один раз — при первой оплате refereeId.

@Entity
@Table(
    name = "referral_activations",
    indexes = [
        Index(name = "idx_ref_act_referee",  columnList = "referee_id",  unique = true),
        Index(name = "idx_ref_act_referrer", columnList = "referrer_id"),
    ],
)
class ReferralActivation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // Тот, чья реферальная ссылка
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referrer_id", nullable = false)
    val referrer: User,

    // Тот, кто пришёл по ссылке
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referee_id", nullable = false, unique = true)
    val referee: User,

    // Бонус реферреру уже начислен?
    @Column(name = "bonus_granted", nullable = false)
    var bonusGranted: Boolean = false,

    // Когда пришёл по ссылке
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    // Когда бонус был начислен
    @Column(name = "bonus_granted_at")
    var bonusGrantedAt: LocalDateTime? = null,
)

@Repository
interface ReferralActivationRepository : JpaRepository<ReferralActivation, Long> {

    // Найти запись по refereeId — нужна при первой оплате
    fun findByRefereeId(refereeId: Long): ReferralActivation?

    // Сколько рефералов привёл реферрер (всего)
    fun countByReferrerId(referrerId: Long): Long

    // Сколько рефералов уже оплатили (бонус начислен)
    fun countByReferrerIdAndBonusGrantedTrue(referrerId: Long): Long

    // Список всех рефералов реферрера для отображения
    @Query("""
        SELECT ra FROM ReferralActivation ra
        JOIN FETCH ra.referee
        WHERE ra.referrer.id = :referrerId
        ORDER BY ra.createdAt DESC
    """)
    fun findByReferrerIdOrderByCreatedAtDesc(@Param("referrerId") referrerId: Long): List<ReferralActivation>
}