package io.getaimly.backend.referral

import io.getaimly.backend.subscription.SubscriptionExpiry
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
        // TWO-SIDED: 7 дней обоим
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
     * Начисляет бонусные дни обоим (реферреру и рефери) при первой оплате рефери.
     *
     * ВАЖНО: если у пользователя ещё нет записи в subscription_expiry
     * (например, у него ручная ACTIVE подписка без Tribute), запись создаётся
     * с нулевым expiresAt — бонус сохраняется в буфере и будет применён при
     * следующем продлении подписки через scheduler.
     */
    @Transactional
    fun grantBonusIfEligible(referee: User): Boolean {
        val activation = referralActivationRepository.findByRefereeId(referee.id) ?: run {
            log.debug("[REFERRAL] Нет реферральной активации для refereeId=${referee.id}")
            return false
        }
        if (activation.bonusGranted) {
            log.debug("[REFERRAL] Бонус уже начислен: refereeId=${referee.id} referrerId=${activation.referrer.id}")
            return false
        }

        val referrer = activation.referrer

        // ─── Бонус рефереру ───────────────────────────────────────────────────
        val referrerExpiry = expiryRepository.findByUserId(referrer.id)
            ?: run {
                // Создаём запись если её нет — бонус накапливается в буфере
                log.info(
                    "[REFERRAL] Запись subscription_expiry не найдена для реферрера referrerId=${referrer.id} " +
                            "— создаём с bonusDaysBuffer=$BONUS_DAYS_PER_REFERRAL"
                )
                SubscriptionExpiry(
                    user             = referrer,
                    expiresAt        = LocalDateTime.now(), // будет перезаписан при оплате
                    bonusDaysBuffer  = BONUS_DAYS_PER_REFERRAL,
                ).also { expiryRepository.save(it) }
            }

        if (referrerExpiry.bonusDaysBuffer == 0 || referrerExpiry.id != 0L) {
            // Запись уже существовала — просто добавляем дни
            referrerExpiry.bonusDaysBuffer += BONUS_DAYS_PER_REFERRAL
            expiryRepository.save(referrerExpiry)
        }
        // (иначе запись была создана выше уже с нужным буфером)

        log.info(
            "[REFERRAL] ✅ Бонус рефереру: referrerId=${referrer.id} email=${referrer.email} " +
                    "+${BONUS_DAYS_PER_REFERRAL} дней буфер=${referrerExpiry.bonusDaysBuffer}"
        )

        // ─── Бонус рефери (TWO-SIDED) ─────────────────────────────────────────
        val refereeExpiry = expiryRepository.findByUserId(referee.id)
        if (refereeExpiry != null) {
            refereeExpiry.bonusDaysBuffer += BONUS_DAYS_PER_REFERRAL
            expiryRepository.save(refereeExpiry)
            log.info(
                "[REFERRAL] ✅ Бонус рефери: refereeId=${referee.id} email=${referee.email} " +
                        "+${BONUS_DAYS_PER_REFERRAL} дней буфер=${refereeExpiry.bonusDaysBuffer}"
            )
        } else {
            log.warn(
                "[REFERRAL] Запись subscription_expiry не найдена для рефери refereeId=${referee.id} " +
                        "— бонус рефери не начислен (нет активной подписки)"
            )
        }

        activation.bonusGranted   = true
        activation.bonusGrantedAt = LocalDateTime.now()
        referralActivationRepository.save(activation)

        log.info(
            "[REFERRAL] ✅ TWO-SIDED бонус начислен: " +
                    "referrer=${referrer.email} referee=${referee.email} +${BONUS_DAYS_PER_REFERRAL} дней каждому"
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