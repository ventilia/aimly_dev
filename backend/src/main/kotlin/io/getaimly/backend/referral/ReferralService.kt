package io.getaimly.backend.referral

import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class ReferralStatsDto(
    val code:           String,
    val referralLink:   String,
    val totalReferrals: Long,
    val paidReferrals:  Long,
    val bonusDaysLeft:  Int,
)

@Service
class ReferralService(
    private val referralCodeRepository:       ReferralCodeRepository,
    private val referralActivationRepository: ReferralActivationRepository,
    private val expiryRepository:             SubscriptionExpiryRepository,
    private val userRepository:               UserRepository,
    // ✅ Берём из application.properties — так же как в AuthController и AimlyBot
    @Value("\${telegram.bot.username}") private val botUsername: String,
) {
    private val log = LoggerFactory.getLogger(ReferralService::class.java)

    companion object {
        const val BONUS_DAYS_PER_REFERRAL = 7
    }



    @Transactional
    fun getOrCreateCode(user: User): ReferralCode {
        referralCodeRepository.findByUserId(user.id)?.let { return it }

        val code = generateUniqueCode()
        val ref  = ReferralCode(user = user, code = code)
        referralCodeRepository.save(ref)
        log.info("[REFERRAL] Создан код: userId=${user.id} email=${user.email} code=$code")
        return ref
    }

    // ─── Получить статистику реферальной программы ───────────────────────────

    fun getStats(user: User): ReferralStatsDto {
        val ref    = getOrCreateCode(user)
        val expiry = expiryRepository.findByUserId(user.id)
        return ReferralStatsDto(
            code           = ref.code,
            referralLink   = buildLink(ref.code),
            totalReferrals = referralActivationRepository.countByReferrerId(user.id),
            paidReferrals  = referralActivationRepository.countByReferrerIdAndBonusGrantedTrue(user.id),
            bonusDaysLeft  = expiry?.bonusDaysBuffer ?: 0,
        )
    }

    // ─── Зафиксировать переход по реферальной ссылке ─────────────────────────

    fun resolveReferralCode(code: String): ReferralCode? =
        referralCodeRepository.findByCode(code)

    @Transactional
    fun registerRefereeIfNew(referrerCode: String, referee: User) {
        val refCodeEntity = referralCodeRepository.findByCode(referrerCode) ?: run {
            log.warn("[REFERRAL] Код не найден: code=$referrerCode refereeId=${referee.id}")
            return
        }
        if (refCodeEntity.user.id == referee.id) {
            log.debug("[REFERRAL] Пользователь пытается использовать собственный код: userId=${referee.id}")
            return
        }

        if (referralActivationRepository.findByRefereeId(referee.id) != null) {
            log.debug("[REFERRAL] Пользователь уже является рефери: refereeId=${referee.id}")
            return
        }

        val activation = ReferralActivation(
            referrer = refCodeEntity.user,
            referee  = referee,
        )
        referralActivationRepository.save(activation)
        log.info("[REFERRAL] Активация зарегистрирована: referrerId=${refCodeEntity.user.id} refereeId=${referee.id} code=$referrerCode")
    }

    // ─── Начислить бонус реферреру при первой оплате рефери ─────────────────

    @Transactional
    fun grantBonusIfEligible(referee: User): Boolean {
        val activation = referralActivationRepository.findByRefereeId(referee.id) ?: return false
        if (activation.bonusGranted) {
            log.debug("[REFERRAL] Бонус уже начислен: refereeId=${referee.id} referrerId=${activation.referrer.id}")
            return false
        }

        val referrer = activation.referrer
        val expiry   = expiryRepository.findByUserId(referrer.id)

        if (expiry != null) {
            expiry.bonusDaysBuffer += BONUS_DAYS_PER_REFERRAL
            expiryRepository.save(expiry)
        }

        activation.bonusGranted   = true
        activation.bonusGrantedAt = LocalDateTime.now()
        referralActivationRepository.save(activation)

        log.info(
            "[REFERRAL] ✅ Бонус начислен: referrerId=${referrer.id} email=${referrer.email} " +
                    "refereeId=${referee.id} +${BONUS_DAYS_PER_REFERRAL} дней " +
                    "буфер=${expiry?.bonusDaysBuffer ?: "н/д (нет подписки)"}"
        )
        return true
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    // ✅ botUsername теперь из @Value, не захардкожен
    fun buildLink(code: String): String = "https://t.me/$botUsername?start=ref_$code"

    private fun generateUniqueCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        repeat(20) {
            val candidate = (1..6).map { chars.random() }.joinToString("")
            if (!referralCodeRepository.existsByCode(candidate)) return candidate
        }
        return (1..10).map { chars.random() }.joinToString("")
    }
}