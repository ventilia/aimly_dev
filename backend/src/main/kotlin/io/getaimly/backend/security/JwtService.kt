package io.getaimly.backend.security

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.expiration-ms}") private val expirationMs: Long,
) {
    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray())
    }

    fun generateToken(userId: Long, email: String): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact()

    fun validateToken(token: String): Boolean =
        runCatching { getClaims(token); true }.getOrDefault(false)

    fun getUserIdFromToken(token: String): Long =
        getClaims(token).subject.toLong()

    fun getEmailFromToken(token: String): String =
        getClaims(token)["email"] as String

    private fun getClaims(token: String) =
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
}