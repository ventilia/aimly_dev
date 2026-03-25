package io.getaimly.backend.referral

import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class ReferralStatsDto(
    val code:           String,
    val referralLink:   String,
    val totalReferrals: Long,  // Всего перешли по ссылке
    val paidReferrals:  Long,  // Из них оплатили (бонус начислен)
    val bonusDaysLeft:  Int,   // Накопленный буфер бонусных дней
)

@Service
class ReferralService(
    private val referralCodeRepository:       ReferralCodeRepository,
    private val referralActivationRepository: ReferralActivationRepository,
    private val expiryRepository:             SubscriptionExpiryRepository,
    private val userRepository:               UserRepository,
) {
    private val log = LoggerFactory.getLogger(ReferralService::class.java)

    companion object {
        const val BONUS_DAYS_PER_REFERRAL = 5
        const val BOT_USERNAME            = "aimly_bot" // совпадает с telegram.bot.username
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
        return ReferralStatsDto(
            code           = ref.code,
            referralLink   = buildLink(ref.code),
            totalReferrals = referralActivationRepository.countByReferrerId(user.id),
            paidReferrals  = referralActivationRepository.countByReferrerIdAndBonusGrantedTrue(user.id),
            bonusDaysLeft  = expiry?.bonusDaysBuffer ?: 0,
        )
    }

    // ─── Зафиксировать переход по реферальной ссылке ─────────────────────────
    // Вызывается когда пользователь ввёл /start ref_CODE в боте.
    // Сохраняем связь «рефери → реферрер» только если:
    //   1. Код существует
    //   2. Пользователь ещё не зарегистрирован (нет userId), либо только что зарегистрирован
    //      и у него ещё нет записи активации
    // refereeUser — пользователь, перешедший по ссылке (может быть null если ещё не зарегистрирован)
    // Возвращает код реферрера для сохранения в сессии

    fun resolveReferralCode(code: String): ReferralCode? =
        referralCodeRepository.findByCode(code)

    @Transactional
    fun registerRefereeIfNew(referrerCode: String, referee: User) {
        // Не засчитываем самого себя
        val refCodeEntity = referralCodeRepository.findByCode(referrerCode) ?: run {
            log.warn("[REFERRAL] Код не найден: code=$referrerCode refereeId=${referee.id}")
            return
        }
        if (refCodeEntity.user.id == referee.id) {
            log.debug("[REFERRAL] Пользователь пытается использовать собственный код: userId=${referee.id}")
            return
        }

        // Уже зарегистрирован как рефери (по другой или той же ссылке)
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
    // Вызывается из TributeWebhookController при new_subscription.
    // Идемпотентно: если bonusGranted=true — ничего не делает.

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
        // Если у реферрера нет записи в expiry (нет подписки) — буфер будет добавлен
        // когда он оформит подписку. Для этого сохраняем факт в activation и начислим
        // буфер лениво при создании expiry. Пока просто логируем — это edge-case,
        // обычно реферрер уже является подписчиком.

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

    fun buildLink(code: String): String = "https://t.me/$BOT_USERNAME?start=ref_$code"

    private fun generateUniqueCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // без O,0,I,1 — визуально похожи
        repeat(20) {
            val candidate = (1..6).map { chars.random() }.joinToString("")
            if (!referralCodeRepository.existsByCode(candidate)) return candidate
        }
        // Крайне маловероятно, но на всякий случай увеличиваем длину
        return (1..10).map { chars.random() }.joinToString("")
    }
}