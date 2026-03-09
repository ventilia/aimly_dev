package io.getaimly.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.Optional

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): Optional<User>
    fun findByTelegramId(telegramId: Long): Optional<User>
    fun existsByEmail(email: String): Boolean
    fun findByGoogleId(googleId: String): Optional<User>

    

    fun existsByTelegramId(telegramId: Long): Boolean

    @Modifying
    @Query("UPDATE User u SET u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    fun touchUpdatedAt(id: Long)
}