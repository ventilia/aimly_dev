package io.getaimly.backend.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap


@Service
class RateLimitService(
    @Value("\${rate-limit.login.max-attempts}") private val maxAttempts: Int,
    @Value("\${rate-limit.login.window-minutes}") private val windowMinutes: Long,
    @Value("\${rate-limit.login.block-minutes}") private val blockMinutes: Long,
) {

    private val attempts = ConcurrentHashMap<String, MutableList<LocalDateTime>>()
    private val blocked  = ConcurrentHashMap<String, LocalDateTime>()

    fun isBlocked(key: String): Boolean {
        val blockedUntil = blocked[key] ?: return false
        return if (LocalDateTime.now().isBefore(blockedUntil)) {
            true
        } else {
            blocked.remove(key)
            attempts.remove(key)
            false
        }
    }

    fun recordFailedAttempt(key: String) {
        val now = LocalDateTime.now()
        val windowStart = now.minusMinutes(windowMinutes)

        val list = attempts.getOrPut(key) { mutableListOf() }

        list.removeIf { it.isBefore(windowStart) }
        list.add(now)

        if (list.size >= maxAttempts) {
            blocked[key] = now.plusMinutes(blockMinutes)
            attempts.remove(key)
        }
    }

    fun recordSuccess(key: String) {
        attempts.remove(key)
        blocked.remove(key)
    }

    fun getRemainingAttempts(key: String): Int {
        val now = LocalDateTime.now()
        val windowStart = now.minusMinutes(windowMinutes)
        val list = attempts[key] ?: return maxAttempts
        val recent = list.count { it.isAfter(windowStart) }
        return maxOf(0, maxAttempts - recent)
    }
}