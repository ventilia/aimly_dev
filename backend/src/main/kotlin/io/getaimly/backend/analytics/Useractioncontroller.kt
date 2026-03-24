package io.getaimly.backend.analytics

import io.getaimly.backend.user.User
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ─── DTO ─────────────────────────────────────────────────────────────────────

data class UserActionRequest(
    /** Тип события: CLICK, PAGE_VIEW, MODAL_OPEN, FORM_SUBMIT, ... */
    val action:    String,
    /** Метка кнопки/элемента или имя страницы */
    val label:     String?    = null,
    /** Текущий путь в браузере, например /dashboard/leads */
    val page:      String?    = null,
    /** Свободные метаданные — план, id чата и т.д. */
    val meta:      Map<String, String>? = null,
    /** userId из фронта — используется только для анонимных запросов */
    val userId:    Long?      = null,
    /** Email из фронта — нужен для анонимных запросов, маскируем в логе */
    val userEmail: String?    = null,
    /** Клиентский timestamp ISO-8601 */
    val ts:        String?    = null,
)

// ─── Rate limiter (простой in-memory, без внешних зависимостей) ──────────────

private object ActionRateLimiter {
    private data class Bucket(val count: AtomicInteger = AtomicInteger(0), var windowStart: Long = System.currentTimeMillis())

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private const val WINDOW_MS = 60_000L   // 1 минута
    private const val MAX_PER_WINDOW = 200  // макс событий с одного IP за минуту

    fun isAllowed(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val bucket = buckets.getOrPut(ip) { Bucket() }
        synchronized(bucket) {
            if (now - bucket.windowStart > WINDOW_MS) {
                bucket.windowStart = now
                bucket.count.set(0)
            }
            return bucket.count.incrementAndGet() <= MAX_PER_WINDOW
        }
    }

    // периодически чистим старые записи (вызывается из контроллера)
    fun evictStale() {
        val threshold = System.currentTimeMillis() - WINDOW_MS * 5
        buckets.entries.removeIf { (_, b) -> b.windowStart < threshold }
    }
}

// ─── Белый список допустимых action ──────────────────────────────────────────

private val ALLOWED_ACTIONS = setOf(
    // Навигация
    "PAGE_VIEW", "PAGE_LEAVE",
    // Клики
    "CLICK", "BUTTON_CLICK", "LINK_CLICK",
    // Модалки
    "MODAL_OPEN", "MODAL_CLOSE",
    // Формы
    "FORM_SUBMIT", "FORM_ERROR",
    // Аутентификация
    "LOGIN_ATTEMPT", "LOGIN_SUCCESS", "LOGIN_FAIL",
    "REGISTER_ATTEMPT", "REGISTER_SUCCESS",
    "LOGOUT",
    "GOOGLE_AUTH_CLICK",
    "EMAIL_VERIFY_SUBMIT",
    "FORGOT_PASSWORD_SUBMIT",
    "RESET_PASSWORD_SUBMIT",
    // Дашборд — лиды
    "LEADS_VIEW", "LEAD_COPY", "LEAD_EXPORT",
    // Дашборд — чаты
    "CHAT_ADD_START", "CHAT_ADD_SUCCESS", "CHAT_ADD_FAIL",
    "CHAT_DELETE",
    "CHAT_SEARCH_START", "CHAT_SEARCH_SUCCESS",
    // Дашборд — ключевые слова
    "KEYWORD_ADD_START", "KEYWORD_ADD_SUCCESS", "KEYWORD_ADD_FAIL",
    "KEYWORD_DELETE",
    "KEYWORD_EXPAND_CLICK",
    // Подписка / оплата
    "PLAN_VIEW", "PLAN_SELECT", "BUY_CLICK", "CHECKOUT_OPEN", "CHECKOUT_SUBMIT",
    "SUBSCRIPTION_UPGRADE_CLICK",
    // Профиль
    "PROFILE_EDIT_START", "PROFILE_EDIT_SAVE",
    "TELEGRAM_LINK_CLICK", "TELEGRAM_UNLINK_CLICK",
    "PASSWORD_CHANGE_SUBMIT",
    // Уведомления
    "NOTIFICATION_OPEN", "NOTIFICATION_MARK_READ",
    // Лэндинг
    "HERO_CTA_CLICK", "PRICING_CTA_CLICK", "SECTION_VIEW",
    // Ошибки
    "CLIENT_ERROR",
)

// ─── Controller ───────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/log")
class UserActionController {

    private val log = LoggerFactory.getLogger(UserActionController::class.java)

    // счётчик evict — раз в 500 запросов чистим старые IP-бакеты
    private val evictCounter = AtomicInteger(0)

    @PostMapping("/action")
    fun logAction(
        @RequestBody          req:      UserActionRequest,
        @AuthenticationPrincipal(errorOnInvalidType = false)
        authUser: User?,
        request:              HttpServletRequest,
    ): ResponseEntity<Void> {

        val ip = resolveIp(request)

        // ── rate limit ────────────────────────────────────────────────────────
        if (!ActionRateLimiter.isAllowed(ip)) {
            log.warn("[UI_ACTION] [RATE_LIMIT] ip=$ip action=${req.action}")
            return ResponseEntity.status(429).build()
        }

        // ── evict раз в 500 запросов ──────────────────────────────────────────
        if (evictCounter.incrementAndGet() % 500 == 0) {
            ActionRateLimiter.evictStale()
        }

        // ── валидация action ──────────────────────────────────────────────────
        val action = req.action.uppercase().take(64)
        if (action !in ALLOWED_ACTIONS) {
            log.warn("[UI_ACTION] [UNKNOWN_ACTION] action=$action ip=$ip")
            return ResponseEntity.badRequest().build()
        }

        // ── определяем пользователя ───────────────────────────────────────────
        // Приоритет: аутентифицированный через JWT > переданный userId/email
        val resolvedUserId    = authUser?.id    ?: req.userId
        val resolvedUserEmail = authUser?.email ?: req.userEmail?.let { maskEmail(it) }
        val isAuthenticated   = authUser != null

        // ── очищаем метаданные ────────────────────────────────────────────────
        val safeMeta = req.meta
            ?.entries
            ?.take(10) // не более 10 ключей
            ?.filter { (k, v) ->
                k.length <= 64 && v.length <= 256 &&
                        !k.contains(Regex("(?i)password|token|secret|card|cvv|pin"))
            }
            ?.joinToString(", ") { (k, v) -> "$k=$v" }

        val label = req.label?.take(128)
        val page  = req.page?.take(256)

        // ── пишем лог ─────────────────────────────────────────────────────────
        log.info(
            "[UI_ACTION] action={} label={} page={} userId={} email={} auth={} ip={} ts={} meta=[{}]",
            action,
            label ?: "-",
            page  ?: "-",
            resolvedUserId    ?: "anon",
            resolvedUserEmail ?: "anon",
            isAuthenticated,
            ip,
            req.ts?.take(32) ?: Instant.now().toString(),
            safeMeta ?: "",
        )

        return ResponseEntity.noContent().build()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun resolveIp(request: HttpServletRequest): String {
        // учитываем reverse-proxy заголовки, берём только первый IP
        val forwarded = request.getHeader("X-Forwarded-For")
            ?.split(",")?.firstOrNull()?.trim()
        return forwarded?.takeIf { it.isNotBlank() && it.length <= 45 }
            ?: request.remoteAddr
            ?: "unknown"
    }

    /** user@example.com → u***@example.com */
    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at < 1) return "***"
        val local  = email.substring(0, at)
        val domain = email.substring(at)
        val visible = local.take(1)
        return "$visible***$domain"
    }
}