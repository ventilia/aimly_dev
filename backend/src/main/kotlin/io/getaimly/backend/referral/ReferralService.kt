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

    @Transactional
    fun getOrCreateCode(user: User): ReferralCode {
        referralCodeRepository.findByUserId(user.id)?.let { return it }

        val code = generateUniqueCode()
        val ref  = ReferralCode(user = user, code = code)
        referralCodeRepository.save(ref)
        log.info("[REFERRAL] Создан код: userId=${user.id} email=${user.email} code=$code")
        return ref
    }

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

    /**
     * Two-sided referral bonus:
     * - Реферрер (тот, чья ссылка) получает +7 дней в bonusDaysBuffer
     * - Рефери  (тот, кто пришёл по ссылке и оплатил) тоже получает +7 дней
     *
     * Идемпотентно: bonusGranted=true предотвращает повторное начисление.
     */
    @Transactional
    fun grantBonusIfEligible(referee: User): Boolean {
        val activation = referralActivationRepository.findByRefereeId(referee.id) ?: return false
        if (activation.bonusGranted) {
            log.debug("[REFERRAL] Бонус уже начислен: refereeId=${referee.id} referrerId=${activation.referrer.id}")
            return false
        }

        val referrer = activation.referrer

        // ── Начисляем реферреру ──────────────────────────────────────────────
        val referrerExpiry = expiryRepository.findByUserId(referrer.id)
        if (referrerExpiry != null) {
            referrerExpiry.bonusDaysBuffer += BONUS_DAYS_PER_REFERRAL
            expiryRepository.save(referrerExpiry)
            log.info(
                "[REFERRAL] ✅ Бонус реферреру: referrerId=${referrer.id} email=${referrer.email} " +
                        "+${BONUS_DAYS_PER_REFERRAL} дней, буфер=${referrerExpiry.bonusDaysBuffer}"
            )
        } else {
            log.info("[REFERRAL] Реферрер без подписки — бонусный буфер не создаём: referrerId=${referrer.id}")
        }

        // ── Начисляем рефери ─────────────────────────────────────────────────
        val refereeExpiry = expiryRepository.findByUserId(referee.id)
        if (refereeExpiry != null) {
            refereeExpiry.bonusDaysBuffer += BONUS_DAYS_PER_REFERRAL
            expiryRepository.save(refereeExpiry)
            log.info(
                "[REFERRAL] ✅ Бонус рефери: refereeId=${referee.id} email=${referee.email} " +
                        "+${BONUS_DAYS_PER_REFERRAL} дней, буфер=${refereeExpiry.bonusDaysBuffer}"
            )
        } else {
            log.info("[REFERRAL] Рефери без записи подписки — бонус будет добавлен после создания подписки: refereeId=${referee.id}")
        }

        activation.bonusGranted   = true
        activation.bonusGrantedAt = LocalDateTime.now()
        referralActivationRepository.save(activation)

        log.info(
            "[REFERRAL] ✅ Two-sided бонус начислен: referrerId=${referrer.id} refereeId=${referee.id} " +
                    "+${BONUS_DAYS_PER_REFERRAL} дней каждому"
        )
        return true
    }

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