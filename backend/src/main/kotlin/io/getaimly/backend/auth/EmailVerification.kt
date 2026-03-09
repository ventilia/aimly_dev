package io.getaimly.backend.auth

import io.getaimly.backend.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "email_verifications")
class EmailVerification(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,


    @Column(nullable = false, length = 64)
    val code: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: VerificationType,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: LocalDateTime,

    @Column(nullable = false)
    var used: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun isValid(): Boolean = !used && expiresAt.isAfter(LocalDateTime.now())
}


enum class VerificationType { EMAIL_CONFIRM, PASSWORD_RESET, TG_LINK }