package io.getaimly.backend.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime
import java.util.Optional

interface EmailVerificationRepository : JpaRepository<EmailVerification, Long> {
    fun findByCodeAndType(
        code: String,
        type: VerificationType,
    ): Optional<EmailVerification>

    // последний неиспользованный актуальный код для пользователя
    fun findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
        userId: Long,
        type: VerificationType,
        now: LocalDateTime,
    ): Optional<EmailVerification>

    @Modifying
    @Query("""
        DELETE FROM EmailVerification e
        WHERE e.user.id = :userId AND e.type = :type AND e.used = false
    """)
    fun deleteAllUnusedByUserIdAndType(userId: Long, type: VerificationType)

    @Modifying
    @Query("DELETE FROM EmailVerification e WHERE e.expiresAt < :before")
    fun deleteExpired(before: LocalDateTime)
}