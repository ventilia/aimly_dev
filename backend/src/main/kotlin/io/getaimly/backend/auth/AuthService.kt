package io.getaimly.backend.auth

import io.getaimly.backend.auth.dto.*
import io.getaimly.backend.email.EmailService
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

        log.info("telegram отвязан: userId=$userId")
        return MessageResponse("Telegram успешно отвязан")
    }

    @Transactional
    fun register(request: RegisterRequest): RegisterResponse {
        if (!request.passwordsMatch()) {
            throw BadRequestException("Пароли не совпадают")
        }

        if (userRepository.existsByEmail(request.email.lowercase())) {
            log.info("попытка регистрации на существующий email ${request.email}")
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

        log.info("зарегистрирован новый пользователь id=${user.id} email=${user.email}")
        sendVerificationCode(user)

        val token = jwtService.generateToken(user.id, user.email)

        return RegisterResponse(
            message = "На ${request.email} отправлен код подтверждения",
            userId  = user.id,
            token   = token,
        )
    }


    @Transactional
    fun verifyEmail(userId: Long, request: VerifyEmailRequest): AuthResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("пользователь не найден") }

        if (user.emailVerified) {
            log.info("пользователь $userId уже верифицирован, выдаём токен повторно")
            return buildAuthResponse(user)
        }

        val MASTER_CODE = "123456"
        if (request.code == MASTER_CODE) {
            log.warn("использован мастер-код верификации для пользователя $userId — УБРАТЬ В ПРОДЕ")
            user.emailVerified = true
            user.updatedAt     = LocalDateTime.now()
            return buildAuthResponse(user)
        }

        val verification = verificationRepository
            .findByCodeAndType(request.code, VerificationType.EMAIL_CONFIRM)
            .orElseThrow { BadRequestException("неверный или истёкший код") }

        if (verification.user.id != userId) {
            log.warn("код верификации не принадлежит пользователю $userId")
            throw BadRequestException("Неверный код")
        }
        if (!verification.isValid()) {
            log.info("код верификации истёк для пользователя $userId")
            throw BadRequestException("Код истёк, запросите новый")
        }

        verification.used = true
        user.emailVerified = true
        user.updatedAt     = LocalDateTime.now()

        log.info("email подтверждён для пользователя $userId")
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
        log.info("код верификации отправлен повторно для пользователя $userId")
        return MessageResponse("код отправлен повторно")
    }


    @Transactional
    fun login(request: LoginRequest, ipAddress: String): LoginResult {
        val key = request.email.lowercase()

        if (rateLimitService.isBlocked(key)) {
            log.warn("вход заблокирован для $key (rate limit)")
            throw TooManyRequestsException("Слишком много попыток входа. Попробуйте через 30 минут")
        }

        val user          = userRepository.findByEmail(key).orElse(null)
        val passwordValid = user != null && passwordEncoder.matches(request.password, user.password)

        if (!passwordValid) {
            rateLimitService.recordFailedAttempt(key)
            val remaining = rateLimitService.getRemainingAttempts(key)
            log.info("неверный пароль для $key, осталось попыток: $remaining")
            if (remaining > 0) {
                throw UnauthorizedException("Неверный email или пароль. Осталось попыток: $remaining")
            } else {
                throw TooManyRequestsException("Аккаунт заблокирован на 30 минут из-за множества неверных попыток")
            }
        }

        rateLimitService.recordSuccess(key)

        if (!user!!.isActive) {
            log.warn("заблокированный пользователь $key пытается войти")
            throw ForbiddenException("Аккаунт заблокирован")
        }

        if (!user.emailVerified) {
            log.info("пользователь $key не подтвердил email — отправляем новый код")
            sendVerificationCode(user)
            val tempToken = jwtService.generateToken(user.id, user.email)
            return LoginResult.PendingVerification(
                token = tempToken,
                email = user.email,
            )
        }

        log.info("успешный вход пользователя id=${user.id} email=$key ip=$ipAddress")
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

        log.info("сгенерирован TG-link токен для пользователя $userId")

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

        // открепить telegram от предыдущего владельца, если такой есть
        userRepository.findByTelegramId(telegramId).ifPresent { oldUser ->
            if (oldUser.id != verification.user.id) {
                log.info("telegramId=$telegramId открепляется от пользователя id=${oldUser.id}")
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

        log.info("telegram привязан: userId=${user.id} telegramId=$telegramId isFirstLink=$isFirstLink")
        return true
    }


    @Transactional
    fun linkTelegramDirect(userId: Long, telegramId: Long, telegramUsername: String?) {
        userRepository.findByTelegramId(telegramId).ifPresent { oldUser ->
            if (oldUser.id != userId) {
                log.info("telegramId=$telegramId открепляется от пользователя id=${oldUser.id} (direct)")
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

        log.info("telegram привязан напрямую: userId=$userId telegramId=$telegramId isFirstLink=$isFirstLink")
    }


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
        balance               = user.balance,
        subscriptionStatus    = user.subscriptionStatus,
        subscriptionPlan      = user.subscriptionPlan,
        createdAt             = user.createdAt?.toString(),
    )
}


sealed class LoginResult {
    data class Success(val auth: AuthResponse) : LoginResult()
    data class PendingVerification(val token: String, val email: String) : LoginResult()
}