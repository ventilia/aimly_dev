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
    @Value("\${telegram.bot.username}") private val botUsername: String,
) {
    private val log = LoggerFactory.getLogger(ReferralService::class.java)

    companion object {
        const val BONUS_DAYS_PER_REFERRAL = 7
    }

    // ─── Получить или создать реферальный код пользователя ───────────────────

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
        val stats = ReferralStatsDto(
            code           = ref.code,
            referralLink   = buildLink(ref.code),
            totalReferrals = referralActivationRepository.countByReferrerId(user.id),
            paidReferrals  = referralActivationRepository.countByReferrerIdAndBonusGrantedTrue(user.id),
            bonusDaysLeft  = expiry?.bonusDaysBuffer ?: 0,
        )
        log.debug(
            "[REFERRAL] Статистика: userId=${user.id} email=${user.email} " +
                    "code=${ref.code} всего=${stats.totalReferrals} " +
                    "оплатили=${stats.paidReferrals} буфер=${stats.bonusDaysLeft} дн."
        )
        return stats
    }

    // ─── Зафиксировать переход по реферальной ссылке ─────────────────────────

    fun resolveReferralCode(code: String): ReferralCode? {
        val found = referralCodeRepository.findByCode(code)
        if (found == null) {
            log.warn("[REFERRAL] Код не найден при resolveReferralCode: code=$code")
        }
        return found
    }

    @Transactional
    fun registerRefereeIfNew(referrerCode: String, referee: User) {
        val refCodeEntity = referralCodeRepository.findByCode(referrerCode) ?: run {
            log.warn("[REFERRAL] Код не найден при регистрации рефери: code=$referrerCode refereeId=${referee.id} email=${referee.email}")
            return
        }

        if (refCodeEntity.user.id == referee.id) {
            log.info("[REFERRAL] Пользователь пытается использовать собственный код: userId=${referee.id} email=${referee.email}")
            return
        }

        val existing = referralActivationRepository.findByRefereeId(referee.id)
        if (existing != null) {
            log.debug(
                "[REFERRAL] Пользователь уже является рефери: refereeId=${referee.id} email=${referee.email} " +
                        "referrerId=${existing.referrer.id} bonusGranted=${existing.bonusGranted}"
            )
            return
        }

        val activation = ReferralActivation(
            referrer = refCodeEntity.user,
            referee  = referee,
        )
        referralActivationRepository.save(activation)
        log.info(
            "[REFERRAL] ✅ Активация зарегистрирована: " +
                    "referrerId=${refCodeEntity.user.id} email=${refCodeEntity.user.email} " +
                    "refereeId=${referee.id} refereeEmail=${referee.email} code=$referrerCode"
        )
    }

    // ─── Начислить бонус реферреру при первой оплате рефери ─────────────────

    @Transactional
    fun grantBonusIfEligible(referee: User): Boolean {
        val activation = referralActivationRepository.findByRefereeId(referee.id)
        if (activation == null) {
            log.debug("[REFERRAL] Нет активации для рефери: refereeId=${referee.id} email=${referee.email} — бонус не начисляется")
            return false
        }

        if (activation.bonusGranted) {
            log.debug(
                "[REFERRAL] Бонус уже был начислен ранее: refereeId=${referee.id} " +
                        "referrerId=${activation.referrer.id} bonusGrantedAt=${activation.bonusGrantedAt}"
            )
            return false
        }

        val referrer = activation.referrer
        val expiry   = expiryRepository.findByUserId(referrer.id)

        val bufferBefore = expiry?.bonusDaysBuffer ?: 0

        if (expiry != null) {
            expiry.bonusDaysBuffer += BONUS_DAYS_PER_REFERRAL
            expiryRepository.save(expiry)
            log.info(
                "[REFERRAL] ✅ Бонус начислен (буфер обновлён): " +
                        "referrerId=${referrer.id} email=${referrer.email} " +
                        "refereeId=${referee.id} refereeEmail=${referee.email} " +
                        "+${BONUS_DAYS_PER_REFERRAL} дней (буфер: $bufferBefore → ${expiry.bonusDaysBuffer})"
            )
        } else {
            log.warn(
                "[REFERRAL] ⚠️ Бонус начислен, но у реферрера нет подписки (нет записи SubscriptionExpiry): " +
                        "referrerId=${referrer.id} email=${referrer.email} " +
                        "refereeId=${referee.id} refereeEmail=${referee.email} " +
                        "+${BONUS_DAYS_PER_REFERRAL} дней — будут начислены при активации подписки"
            )
        }

        activation.bonusGranted   = true
        activation.bonusGrantedAt = LocalDateTime.now()
        referralActivationRepository.save(activation)

        return true
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

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