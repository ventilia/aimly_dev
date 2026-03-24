package io.getaimly.backend.security

import io.getaimly.backend.user.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtService:      JwtService,
    private val userRepository:  UserRepository,
    @Value("\${app.cookie.domain:}") private val cookieDomain: String,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthFilter::class.java)

    private val COOKIE_NAME = "aimly_auth"

    private val unverifiedAllowed = setOf(
        "/api/v1/auth/verify-email",
        "/api/v1/auth/resend-code",
        "/api/v1/auth/me",
    )

    private val publicAuthEndpoints = setOf(
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/verify-email",
        "/api/v1/auth/resend-code",
        "/api/v1/auth/logout",
    )

    private val internalPrefixes = listOf("/internal/")

    override fun doFilterInternal(
        request:  HttpServletRequest,
        response: HttpServletResponse,
        chain:    FilterChain,
    ) {
        val token = extractToken(request)

        if (token == null) {
            val isInternal = internalPrefixes.any { request.requestURI.startsWith(it) }
            if (!isInternal) {
                log.trace("нет токена в запросе к ${request.requestURI}")
            }
            chain.doFilter(request, response)
            return
        }

        if (!jwtService.validateToken(token)) {
            log.warn("невалидный JWT для запроса к ${request.requestURI}")
            if (!isPublicAuthEndpoint(request.requestURI)) {
                clearAuthCookie(response)
            }
            chain.doFilter(request, response)
            return
        }

        val userId = jwtService.getUserIdFromToken(token)

        userRepository.findById(userId).ifPresentOrElse({ user ->
            if (!user.isActive) {
                log.warn("заблокированный пользователь $userId пытается авторизоваться")
                chain.doFilter(request, response)
                return@ifPresentOrElse
            }

            val path            = request.requestURI
            val allowUnverified = unverifiedAllowed.any { path.startsWith(it) }

            if (!user.emailVerified && !allowUnverified) {
                log.trace("пользователь $userId не подтвердил почту, запрос к $path отклонён")
                chain.doFilter(request, response)
                return@ifPresentOrElse
            }

            val auth = UsernamePasswordAuthenticationToken(
                user,
                null,
                listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")),
            )
            SecurityContextHolder.getContext().authentication = auth
            log.trace("пользователь $userId аутентифицирован через куку, emailVerified=${user.emailVerified}")
        }, {
            if (!isPublicAuthEndpoint(request.requestURI)) {
                log.warn("токен содержит несуществующий userId=$userId — очищаем куку")
                clearAuthCookie(response)
            } else {
                log.warn("токен содержит несуществующий userId=$userId — пропускаем (публичный эндпоинт)")
            }
        })

        chain.doFilter(request, response)
    }

    private fun isPublicAuthEndpoint(uri: String): Boolean =
        publicAuthEndpoints.any { uri.startsWith(it) }

    private fun clearAuthCookie(response: HttpServletResponse) {
        val cookieHeader = buildString {
            append("$COOKIE_NAME=")
            append("; Path=/")
            append("; Max-Age=0")
            append("; HttpOnly")
            append("; Secure")
            append("; SameSite=None")
            if (cookieDomain.isNotBlank()) append("; Domain=$cookieDomain")
        }
        response.addHeader("Set-Cookie", cookieHeader)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        val cookieToken = request.cookies
            ?.find { it.name == COOKIE_NAME }
            ?.value
            ?.takeIf { it.isNotBlank() }

        if (cookieToken != null) return cookieToken

        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
    }
}