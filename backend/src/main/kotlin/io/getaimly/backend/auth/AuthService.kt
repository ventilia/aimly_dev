package io.getaimly.backend.auth

import io.getaimly.backend.auth.dto.*
import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.email.EmailService
import io.getaimly.backend.referral.ReferralService
import io.getaimly.backend.security.JwtService
import io.getaimly.backend.security.RateLimitService
import io.getaimly.backend.subscription.SubscriptionService
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.LocalDateTime

@Service
class AuthService(
    private val userRepository:           UserRepository,
    private val verificationRepository:   EmailVerificationRepository,
    private val passwordEncoder:          PasswordEncoder,
    private val jwtService:               JwtService,
    private val emailService:             EmailService,
    private val rateLimitService:         RateLimitService,

    @Lazy private val subscriptionService: SubscriptionService,
    @Lazy private val bot: AimlyBot,
    @Lazy private val referralService: ReferralService,
) {
    private val log    = LoggerFactory.getLogger(AuthService::class.java)
    private val random = SecureRandom()

    @Transactional
    fun unlinkTelegram(userId: Long): MessageResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("пользователь не найден") }

        if (user.telegramId == null) {
            throw BadRequestException("Telegram не привязан")
        }

        user.telegramId       = null
        user.telegramUsername = null
        user.telegramLinkedAt = null
        user.updatedAt        = LocalDateTime.now()
        userRepository.save(user)

        log.info("[AUTH] Telegram отвязан: userId=$userId email=${user.email}")
        return MessageResponse("Telegram успешно отвязан")
    }

    @Transactional
    fun register(request: RegisterRequest): RegisterResponse {
        if (!request.passwordsMatch()) {
            throw BadRequestException("Пароли не совпадают")
        }

        if (userRepository.existsByEmail(request.email.lowercase())) {
            log.info("[AUTH] Попытка регистрации на занятый email: email=${request.email.lowercase()}")
            return RegisterResponse(
                message = "Если этот адрес не зарегистрирован, на него отправлен код подтверждения",
                userId  = null,
                token   = null,
            )
        }

        val user = userRepository.save(
            User(
                email     = request.email.lowercase(),
                password  = passwordEncoder.encode(request.password),
                firstName = request.firstName,
            )
        )

        log.info("[AUTH] Регистрация: userId=${user.id} email=${user.email} name=${user.firstName}")

        // ── Реферальный код — применяем ПОСЛЕ создания пользователя ──────────
        if (!request.referralCode.isNullOrBlank()) {
            runCatching { referralService.registerRefereeIfNew(request.referralCode, user) }
                .onSuccess { log.info("[REFERRAL] Рефери зарегистрирован при регистрации: userId=${user.id} code=${request.referralCode}") }
                .onFailure { log.warn("[REFERRAL] Ошибка регистрации рефери при регистрации: ${it.message}") }
        }

        sendVerificationCode(user)

        val token = jwtService.generateToken(user.id, user.email)

        return RegisterResponse(
            message = "На ${request.email} отправлен код подтверждения",
            userId  = user.id,
            token   = token,
        )
    }


    /**
     * Регистрация через Telegram-бота.
     * Аккаунт создаётся с уже привязанным telegramId.
     * Email-верификация считается пройденной (бот подтверждает личность).
     * Trial выдаётся сразу.
     */
    @Transactional
    fun registerViaTelegram(
        email:            String,
        password:         String,
        firstName:        String?,
        telegramId:       Long,
        telegramUsername: String?,
        referralCode:     String?,
    ): RegisterViaTelegramResult {
        val normalizedEmail = email.trim().lowercase()

        if (userRepository.existsByEmail(normalizedEmail)) {
            return RegisterViaTelegramResult.EmailTaken
        }

        userRepository.findByTelegramId(telegramId).orElse(null)?.let {
            return RegisterViaTelegramResult.TelegramAlreadyLinked
        }

        val user = userRepository.save(
            User(
                email            = normalizedEmail,
                password         = passwordEncoder.encode(password),
                firstName        = firstName,
                telegramId       = telegramId,
                telegramUsername = telegramUsername,
                telegramLinkedAt = LocalDateTime.now(),
                emailVerified    = true,
            )
        )

        log.info("[AUTH] Регистрация через бота: userId=${user.id} email=${user.email} tgId=$telegramId")

        if (!referralCode.isNullOrBlank()) {
            runCatching { referralService.registerRefereeIfNew(referralCode, user) }
                .onSuccess { log.info("[REFERRAL] Рефери зарегистрирован при регистрации в боте: userId=${user.id} code=$referralCode") }
                .onFailure { log.warn("[REFERRAL] Ошибка регистрации рефери в боте: ${it.message}") }
        }

        runCatching { subscriptionService.grantTrial(user) }
            .onFailure { log.warn("не удалось выдать trial при регистрации в боте userId=${user.id}: ${it.message}") }

        return RegisterViaTelegramResult.Success(user)
    }


    @Transactional
    fun verifyEmail(userId: Long, request: VerifyEmailRequest): AuthResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("пользователь не найден") }

        if (user.emailVerified) {
            log.info("[AUTH] Email уже подтверждён, выдаём токен повторно: userId=$userId email=${user.email}")
            return buildAuthResponse(user)
        }

        val MASTER_CODE = "123456"
        if (request.code == MASTER_CODE) {
            log.warn("[AUTH] Использован мастер-код верификации: userId=$userId — УБРАТЬ В ПРОДЕ")
            user.emailVerified = true
            user.updatedAt     = LocalDateTime.now()
            return buildAuthResponse(user)
        }

        val verification = verificationRepository
            .findByCodeAndType(request.code, VerificationType.EMAIL_CONFIRM)
            .orElseThrow { BadRequestException("неверный или истёкший код") }

        if (verification.user.id != userId) {
            log.warn("[AUTH] Код верификации не принадлежит пользователю: userId=$userId")
            throw BadRequestException("Неверный код")
        }
        if (!verification.isValid()) {
            log.info("[AUTH] Код верификации истёк: userId=$userId email=${user.email}")
            throw BadRequestException("Код истёк, запросите новый")
        }

        verification.used = true
        user.emailVerified = true
        user.updatedAt     = LocalDateTime.now()

        log.info("[AUTH] Email подтверждён: userId=$userId email=${user.email}")
        return buildAuthResponse(user)
    }


    @Transactional
    fun resendVerificationCode(userId: Long): MessageResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("пользователь не найден") }

        if (user.emailVerified) throw BadRequestException("Email уже подтверждён")

        val lastCode = verificationRepository
            .findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                user.id, VerificationType.EMAIL_CONFIRM, LocalDateTime.now()
            )

        if (lastCode.isPresent) {
            val secondsAgo = java.time.Duration
                .between(lastCode.get().createdAt, LocalDateTime.now())
                .seconds
            if (secondsAgo < 60) {
                throw TooManyRequestsException("Подождите ${60 - secondsAgo} секунд перед повторной отправкой")
            }
        }

        sendVerificationCode(user)
        log.info("[AUTH] Повторный код верификации: userId=$userId email=${user.email}")
        return MessageResponse("код отправлен повторно")
    }


    @Transactional
    fun login(request: LoginRequest, ipAddress: String): LoginResult {
        val key = request.email.lowercase()

        if (rateLimitService.isBlocked(key)) {
            log.warn("[AUTH] Заблокирован rate limit: email=$key ip=$ipAddress")
            throw TooManyRequestsException("Слишком много попыток входа. Попробуйте через 30 минут")
        }

        // ── Шаг 1: пользователь существует? ──────────────────────────────────
        val user = userRepository.findByEmail(key).orElse(null)

        if (user == null) {
            // Не записываем в rate limit — это не ошибка пароля.
            // Но всё равно логируем для мониторинга.
            log.warn("[AUTH] Пользователь не найден: email=$key ip=$ipAddress")
            throw UnauthorizedException("Аккаунт с таким email не найден")
        }

        // ── Шаг 2: пароль верный? ─────────────────────────────────────────────
        val passwordValid = passwordEncoder.matches(request.password, user.password)

        if (!passwordValid) {
            rateLimitService.recordFailedAttempt(key)
            val remaining = rateLimitService.getRemainingAttempts(key)
            log.warn("[AUTH] Неверный пароль: email=$key ip=$ipAddress осталось=$remaining попыток")
            if (remaining > 0) {
                throw UnauthorizedException("Неверный пароль. Осталось попыток: $remaining")
            } else {
                throw TooManyRequestsException("Аккаунт заблокирован на 30 минут из-за множества неверных попыток")
            }
        }

        rateLimitService.recordSuccess(key)

        if (!user.isActive) {
            log.warn("[AUTH] Попытка входа в заблокированный аккаунт: userId=${user.id} email=$key ip=$ipAddress")
            throw ForbiddenException("Аккаунт заблокирован")
        }

        if (!user.emailVerified) {
            log.info("[AUTH] Вход требует верификации email: userId=${user.id} email=$key")
            sendVerificationCode(user)
            val tempToken = jwtService.generateToken(user.id, user.email)
            return LoginResult.PendingVerification(
                token = tempToken,
                email = user.email,
            )
        }

        log.info("[AUTH] Вход: userId=${user.id} email=$key ip=$ipAddress")
        return LoginResult.Success(buildAuthResponse(user))
    }


    @Transactional
    fun generateTelegramLinkToken(userId: Long, botUsername: String): TelegramLinkResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("Пользователь не найден") }

        verificationRepository.deleteAllUnusedByUserIdAndType(userId, VerificationType.TG_LINK)

        val token = generateCode(32, alphanumeric = true)

        verificationRepository.save(
            EmailVerification(
                user      = user,
                code      = token,
                type      = VerificationType.TG_LINK,
                expiresAt = LocalDateTime.now().plusMinutes(10),
            )
        )

        log.info("сгенерирован TG-link токен: userId=$userId email=${user.email}")

        return TelegramLinkResponse(
            linkToken   = token,
            botUsername = botUsername,
        )
    }


    @Transactional
    fun linkTelegram(token: String, telegramId: Long, telegramUsername: String?): Boolean {
        val verification = verificationRepository
            .findByCodeAndType(token, VerificationType.TG_LINK)
            .orElse(null) ?: run {
            log.warn("TG_LINK токен не найден: $token")
            return false
        }

        if (!verification.isValid()) {
            log.warn("TG_LINK токен истёк для token=$token")
            return false
        }

        userRepository.findByTelegramId(telegramId).ifPresent { oldUser ->
            if (oldUser.id != verification.user.id) {
                log.info("telegramId=$telegramId открепляется от userId=${oldUser.id} email=${oldUser.email}")
                oldUser.telegramId       = null
                oldUser.telegramUsername = null
                oldUser.telegramLinkedAt = null
                oldUser.updatedAt        = LocalDateTime.now()
                userRepository.save(oldUser)
            }
        }

        val user        = verification.user
        val isFirstLink = user.telegramId == null

        user.telegramId       = telegramId
        user.telegramUsername = telegramUsername
        user.telegramLinkedAt = LocalDateTime.now()
        user.updatedAt        = LocalDateTime.now()
        verification.used     = true

        if (isFirstLink) {
            runCatching { subscriptionService.grantTrial(user) }
                .onFailure { log.warn("не удалось выдать trial для ${user.email}: ${it.message}") }
        }

        log.info("[AUTH] Telegram привязан: userId=${user.id} email=${user.email} tgId=$telegramId tgUsername=@$telegramUsername isFirstLink=$isFirstLink")
        return true
    }


    @Transactional
    fun linkTelegramDirect(userId: Long, telegramId: Long, telegramUsername: String?) {
        userRepository.findByTelegramId(telegramId).ifPresent { oldUser ->
            if (oldUser.id != userId) {
                log.info("telegramId=$telegramId открепляется от userId=${oldUser.id} email=${oldUser.email} (direct)")
                oldUser.telegramId       = null
                oldUser.telegramUsername = null
                oldUser.telegramLinkedAt = null
                oldUser.updatedAt        = LocalDateTime.now()
                userRepository.save(oldUser)
            }
        }

        val user = userRepository.findById(userId).orElse(null) ?: run {
            log.warn("пользователь $userId не найден при прямой привязке telegram")
            return
        }

        if (user.telegramId == telegramId) {
            log.debug("telegramId=$telegramId уже привязан к userId=$userId, пропускаем")
            return
        }

        val isFirstLink = user.telegramId == null

        user.telegramId       = telegramId
        user.telegramUsername = telegramUsername
        user.telegramLinkedAt = LocalDateTime.now()
        user.updatedAt        = LocalDateTime.now()

        if (isFirstLink) {
            runCatching { subscriptionService.grantTrial(user) }
                .onFailure { log.warn("не удалось выдать trial для ${user.email}: ${it.message}") }
        }

        log.info("[AUTH] Telegram привязан напрямую: userId=$userId email=${user.email} tgId=$telegramId isFirstLink=$isFirstLink")
    }


    // ─── Сброс пароля ─────────────────────────────────────────────────────────────

    @Transactional
    fun requestPasswordReset(email: String): MessageResponse {
        val normalizedEmail = email.trim().lowercase()

        val rateLimitKey = "pwd_reset:$normalizedEmail"
        if (rateLimitService.isBlocked(rateLimitKey)) {
            throw TooManyRequestsException("Слишком много запросов. Попробуйте через 30 минут")
        }

        val user = userRepository.findByEmail(normalizedEmail).orElse(null)

        if (user == null) {
            log.info("запрос сброса пароля для несуществующего email: $normalizedEmail")
            return MessageResponse("Если такой email зарегистрирован, код отправлен на него")
        }

        val lastCode = verificationRepository
            .findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                user.id, VerificationType.PASSWORD_RESET, LocalDateTime.now()
            )
        if (lastCode.isPresent) {
            val secondsAgo = java.time.Duration
                .between(lastCode.get().createdAt, LocalDateTime.now())
                .seconds
            if (secondsAgo < 60) {
                throw TooManyRequestsException("Подождите ${60 - secondsAgo} секунд перед повторной отправкой")
            }
        }

        verificationRepository.deleteAllUnusedByUserIdAndType(user.id, VerificationType.PASSWORD_RESET)

        val code = generateCode(6)
        verificationRepository.save(
            EmailVerification(
                user      = user,
                code      = code,
                type      = VerificationType.PASSWORD_RESET,
                expiresAt = LocalDateTime.now().plusMinutes(15),
            )
        )

        try {
            emailService.sendPasswordResetCode(user.email, code, user.firstName)
        } catch (e: Exception) {
            log.error("не удалось отправить письмо для сброса пароля на ${user.email}: ${e.message}")
        }

        user.telegramId?.let { tgId ->
            runCatching {
                bot.sendText(
                    tgId,
                    "🔐 Сброс пароля AIMLY\n\n" +
                            "Ваш код для сброса пароля:\n\n" +
                            "$code\n\n" +
                            "Код действителен 15 минут.\n" +
                            "Если вы не запрашивали сброс — проигнорируйте это сообщение.",
                )
            }.onFailure {
                log.warn("не удалось отправить код сброса пароля в Telegram userId=${user.id}: ${it.message}")
            }
        }

        rateLimitService.recordFailedAttempt(rateLimitKey)
        log.info("[AUTH] Запрос сброса пароля: userId=${user.id} email=$normalizedEmail tgLinked=${user.telegramId != null}")
        return MessageResponse("Если такой email зарегистрирован, код отправлен на него")
    }


    @Transactional
    fun resetPassword(request: ResetPasswordRequest): AuthResponse {
        if (request.newPassword != request.confirmPassword) {
            throw BadRequestException("Пароли не совпадают")
        }
        if (request.newPassword.length < 8) {
            throw BadRequestException("Пароль должен содержать минимум 8 символов")
        }

        val passwordRegex = Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$")
        if (!passwordRegex.matches(request.newPassword)) {
            throw BadRequestException("Пароль должен содержать строчные, заглавные буквы и цифры")
        }

        val verification = verificationRepository
            .findByCodeAndType(request.code.trim(), VerificationType.PASSWORD_RESET)
            .orElseThrow { BadRequestException("Неверный или истёкший код") }

        if (!verification.isValid()) {
            throw BadRequestException("Код истёк, запросите новый")
        }

        val user = verification.user

        user.password  = passwordEncoder.encode(request.newPassword)
        user.updatedAt = LocalDateTime.now()
        verification.used = true
        userRepository.save(user)

        verificationRepository.deleteAllUnusedByUserIdAndType(user.id, VerificationType.PASSWORD_RESET)

        log.info("[AUTH] Пароль сброшен: userId=${user.id} email=${user.email}")

        user.telegramId?.let { tgId ->
            runCatching {
                bot.sendText(
                    tgId,
                    "✅ Пароль от вашего аккаунта AIMLY успешно изменён.\n\n" +
                            "Если это были не вы — немедленно обратитесь в поддержку: @aimly_support"
                )
            }.onFailure {
                log.warn("не удалось отправить уведомление о смене пароля в Telegram userId=${user.id}: ${it.message}")
            }
        }

        return buildAuthResponse(user)
    }


    // ─── Вспомогательные методы ────────────────────────────────────────────────────

    private fun sendVerificationCode(user: User) {
        verificationRepository.deleteAllUnusedByUserIdAndType(
            user.id, VerificationType.EMAIL_CONFIRM
        )

        val code = generateCode(6)
        verificationRepository.save(
            EmailVerification(
                user      = user,
                code      = code,
                type      = VerificationType.EMAIL_CONFIRM,
                expiresAt = LocalDateTime.now().plusMinutes(15),
            )
        )

        try {
            emailService.sendVerificationCode(user.email, code, user.firstName)
            log.debug("отправлен код верификации для ${user.email}")
        } catch (e: Exception) {
            log.error("не удалось отправить письмо на ${user.email}: ${e.message}")
        }
    }

    private fun generateCode(length: Int, alphanumeric: Boolean = false): String =
        if (alphanumeric) {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
        } else {
            (1..length).map { random.nextInt(10) }.joinToString("")
        }

    fun buildAuthResponse(user: User) = AuthResponse(
        token                 = jwtService.generateToken(user.id, user.email),
        userId                = user.id,
        email                 = user.email,
        firstName             = user.firstName,
        emailVerified         = user.emailVerified,
        telegramLinked        = user.telegramId != null,
        telegramUsername      = user.telegramUsername,
        role                  = user.role.name,
        subscriptionStatus    = user.subscriptionStatus,
        subscriptionPlan      = user.subscriptionPlan,
        createdAt             = user.createdAt?.toString(),
        businessContext       = user.businessContext,
        trialUsed             = user.trialUsed,
    )
}


sealed class LoginResult {
    data class Success(val auth: AuthResponse) : LoginResult()
    data class PendingVerification(val token: String, val email: String) : LoginResult()
}


sealed class RegisterViaTelegramResult {
    data class Success(val user: User) : RegisterViaTelegramResult()
    object EmailTaken            : RegisterViaTelegramResult()
    object TelegramAlreadyLinked : RegisterViaTelegramResult()
}