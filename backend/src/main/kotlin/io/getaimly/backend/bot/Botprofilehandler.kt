package io.getaimly.backend.bot

import io.getaimly.backend.auth.AuthService
import io.getaimly.backend.lead.ChatSubscriptionRepository
import io.getaimly.backend.lead.KeywordRepository
import io.getaimly.backend.lead.LeadRepository
import io.getaimly.backend.lead.LeadService
import io.getaimly.backend.lead.LeadStatus
import io.getaimly.backend.referral.ReferralService
import io.getaimly.backend.subscription.SubscriptionExpiryRepository
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import java.util.concurrent.ConcurrentHashMap


class BotProfileHandler(
    private val sender: BotSender,
    private val sessions: ConcurrentHashMap<Long, UserSession>,
    private val userRepository: UserRepository,
    private val leadRepository: LeadRepository,
    private val subscriptionRepository: ChatSubscriptionRepository,
    private val keywordRepository: KeywordRepository,
    private val expiryRepository: SubscriptionExpiryRepository,
    private val authService: AuthService,
    private val leadService: LeadService,
    private val referralService: ReferralService,
) {

    private val log = LoggerFactory.getLogger(BotProfileHandler::class.java)


    fun showProfile(chatId: Long, msgId: Int, from: User) {
        val user = userRepository.findByTelegramId(from.id).orElse(null)
        if (user == null) {
            log.warn("[BOT][PROFILE] Не авторизован при открытии профиля: chatId=$chatId tgId=${from.id}")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        val chatCount  = subscriptionRepository.countByUserIdAndIsActiveTrue(user.id)
        val kwCount    = keywordRepository.findByUserIdAndIsActiveTrue(user.id).size
        val totalLeads = leadRepository.countByUserId(user.id)
        val newLeads   = leadRepository.countByUserIdAndStatus(user.id, LeadStatus.NEW)
        val expiry     = expiryRepository.findByUserId(user.id)
        val since      = user.createdAt?.toLocalDate()?.toString() ?: "—"

        // Реферальная статистика для краткого отображения в профиле
        val refStats   = referralService.getStats(user)

        log.info("[BOT][PROFILE] Просмотр профиля: userId=${user.id} email=${user.email} план=${user.subscriptionPlan} статус=${user.subscriptionStatus} чатов=$chatCount ключ_слов=$kwCount всего_лидов=$totalLeads новых=$newLeads реф_всего=${refStats.totalReferrals} реф_оплатили=${refStats.paidReferrals} буфер=${refStats.bonusDaysLeft}")

        val plan          = user.subscriptionPlan
        val hasAiFeatures = plan == "MINIMUM" || plan == "START" || user.subscriptionStatus == "TRIAL"

        val subLine = when {
            user.subscriptionStatus == "ACTIVE" -> {
                val till   = expiry?.expiresAt?.toLocalDate()?.toString() ?: "—"
                val buffer = expiry?.bonusDaysBuffer ?: 0
                val bufPart = if (buffer > 0) " \\(\\+$buffer бонусных дн\\.\\)" else ""
                "✅ ${user.subscriptionPlan ?: "ACTIVE"} — до $till$bufPart"
            }
            user.subscriptionStatus == "TRIAL" -> {
                val till = expiry?.expiresAt?.toLocalDate()?.toString() ?: "—"
                "🔵 Пробный период до $till"
            }
            else -> "❌ Нет активной подписки"
        }

        val contextLine = when {
            !hasAiFeatures                        -> "\n🎯 *Бизнес-контекст AI:* 🔒 недоступно"
            !user.businessContext.isNullOrBlank() -> "\n🎯 *Бизнес-контекст AI:* ✅ задан"
            else                                  -> "\n🎯 *Бизнес-контекст AI:* не задан"
        }

        // Реферальная строка — кратко + подсказка про механику
        val refLine = "\n👥 *Рефералы:* ${refStats.paidReferrals} оплатили " +
                "\\(бонус: ${refStats.bonusDaysLeft} дн\\.\\)" +
                "\n_💡 За каждого оплатившего друга — \\+7 дней бесплатно_"

        val text = "👤 *Профиль*\n\n" +
                "📧 ${user.email.md()}\n" +
                "👤 ${(user.firstName ?: "—").md()}\n" +
                "📱 Telegram: ✅ привязан\n" +
                "💳 Подписка: ${subLine.md()}" +
                contextLine +
                refLine + "\n\n" +
                "📊 *Статистика:*\n" +
                "💬 Чатов: $chatCount\n" +
                "🔍 Ключевых слов: $kwCount\n" +
                "📋 Всего лидов: $totalLeads  (🔴 новых: $newLeads)\n" +
                "📅 Аккаунт создан: $since"

        val rows = mutableListOf<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow>()

        if (user.subscriptionStatus != "ACTIVE") {
            rows.add(row(btn("💳 Оплатить подписку", "payment:plans")))
        }

        rows.add(row(btn(
            if (hasAiFeatures && !user.businessContext.isNullOrBlank())
                "🎯 Изменить AI-персонализацию"
            else
                "🎯 Настроить AI-персонализацию",
            "profile:edit_context",
        )))

        // Реферальная программа — отдельная кнопка
        rows.add(row(btn("👥 Реферальная программа", "referral:info")))

        rows.add(row(btn("🔓 Отвязать Telegram", "profile:unlink_tg")))
        rows.add(row(btn("◀️ Главное меню",       "menu:back")))

        sender.editText(chatId, msgId, text, InlineKeyboardMarkup(rows), parseMarkdown = true)
    }


    fun startEditContext(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][PROFILE] Не авторизован при редактировании контекста: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        val plan          = user.subscriptionPlan
        val hasAiFeatures = plan == "MINIMUM" || plan == "START" || user.subscriptionStatus == "TRIAL"

        if (!hasAiFeatures) {
            log.info("[BOT][PROFILE] AI-персонализация недоступна (нет плана): userId=${user.id} email=${user.email} план=$plan")
            sender.editText(
                chatId, msgId,
                "🔒 *AI-персонализация недоступна*\n\n" +
                        "На тарифе *START* и выше вы можете задать описание своего бизнеса — " +
                        "AI будет отбирать только тех клиентов, которые ищут именно то, что вы предлагаете\\.\n\n" +
                        "💡 Пример: «Я frontend-разработчик на React, ищу клиентов с бюджетом от 50к»",
                keyboard(
                    row(btn("💳 Оплатить подписку", "payment:plans")),
                    row(btn("◀️ Назад", "menu:profile")),
                ),
                parseMarkdown = true,
            )
            return
        }

        val hasContext = !user.businessContext.isNullOrBlank()
        log.info("[BOT][PROFILE] Редактирование AI-контекста: userId=${user.id} email=${user.email} есть_контекст=$hasContext")

        val currentContext = user.businessContext
        val currentLine = when {
            currentContext.isNullOrBlank() -> "\n\n_Контекст пока не задан._"
            else -> "\n\n📌 *Текущий контекст:*\n_${currentContext.take(300).md()}${if (currentContext.length > 300) "…" else ""}_"
        }

        sessions[chatId] = UserSession(step = BotStep.WAITING_CONTEXT, msgId = msgId)
        sender.editText(
            chatId, msgId,
            "🎯 *AI-персонализация*$currentLine\n\n" +
                    "Опишите ваш бизнес, услуги и целевую аудиторию\\.\n" +
                    "AI учитывает это при фильтрации лидов и при генерации ключевых слов\\.\n\n" +
                    "✏️ Отправьте новый текст (до 2000 символов):\n\n" +
                    "💡 _Пример: Я frontend-разработчик, специализируюсь на React и Next\\.js\\. " +
                    "Ищу клиентов, которым нужна разработка или доработка веб-приложений\\. " +
                    "Работаю с бюджетами от 50 000 ₽\\._",
            keyboard(
                row(btn("🗑 Очистить контекст", "profile:clear_context")),
                row(btn("❌ Отмена",             "menu:profile")),
            ),
            parseMarkdown = true,
        )
    }

    fun handleContextInput(chatId: Long, text: String) {
        val session    = sessions[chatId] ?: return
        val savedMsgId = session.msgId

        val trimmed = text.trim()
        if (trimmed.length > 2000) {
            log.warn("[BOT][PROFILE] Контекст слишком длинный: chatId=$chatId длина=${trimmed.length}")
            sender.sendText(
                chatId,
                "⚠️ Слишком длинный текст: ${trimmed.length} символов (макс. 2000).\n\nПожалуйста, сократите описание:",
            )
            return
        }

        sessions.remove(chatId)

        val userEntity = userRepository.findByTelegramId(chatId).orElse(null)
            ?: run {
                log.warn("[BOT][PROFILE] Пользователь не найден при сохранении контекста: chatId=$chatId")
                if (savedMsgId != 0) sender.editText(chatId, savedMsgId, "❌ Ошибка. Попробуйте позже.")
                else sender.sendText(chatId, "❌ Ошибка.")
                return
            }

        log.info("[BOT][PROFILE] Сохранение AI-контекста: userId=${userEntity.id} email=${userEntity.email} длина=${trimmed.length}")

        runCatching { leadService.saveBusinessContext(userEntity, trimmed) }
            .onSuccess { result ->
                val isSaved = !result.businessContext.isNullOrBlank()
                val action  = if (isSaved) "сохранён" else "очищен"
                log.info("[BOT][PROFILE] ✅ AI-контекст $action: userId=${userEntity.id} email=${userEntity.email}")

                val confirmText = if (isSaved)
                    "✅ *Бизнес-контекст сохранён!*\n\n" +
                            "🤖 AI теперь учитывает ваш профиль при фильтрации лидов и генерации ключевых слов\\.\n\n" +
                            "📌 _Описание:_\n${trimmed.take(300).md()}${if (trimmed.length > 300) "…" else ""}"
                else
                    "✅ *Бизнес-контекст очищен.*\n\nAI будет работать без персонализации."

                if (savedMsgId != 0) {
                    sender.editText(
                        chatId, savedMsgId,
                        confirmText,
                        keyboard(row(btn("◀️ К профилю", "menu:profile"))),
                        parseMarkdown = true,
                    )
                } else {
                    sender.sendText(chatId, if (isSaved) "✅ Бизнес-контекст сохранён!" else "✅ Бизнес-контекст очищен.")
                }
            }
            .onFailure { e ->
                log.warn("[BOT][PROFILE] ❌ Ошибка сохранения AI-контекста: userId=${userEntity.id} email=${userEntity.email} причина=${e.message}")
                if (savedMsgId != 0) sender.editText(chatId, savedMsgId, "❌ Ошибка: ${e.message}")
                else sender.sendText(chatId, "❌ Ошибка: ${e.message}")
            }
    }

    fun clearContext(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][PROFILE] Не авторизован при очистке контекста: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        sessions.remove(chatId)
        log.info("[BOT][PROFILE] Очистка AI-контекста: userId=${user.id} email=${user.email}")

        runCatching { leadService.saveBusinessContext(user, "") }
            .onSuccess {
                log.info("[BOT][PROFILE] ✅ AI-контекст очищен: userId=${user.id} email=${user.email}")
                sender.editText(
                    chatId, msgId,
                    "✅ *Бизнес-контекст очищен.*\n\nAI будет работать без персонализации.",
                    keyboard(row(btn("◀️ К профилю", "menu:profile"))),
                    parseMarkdown = true,
                )
            }
            .onFailure { e ->
                log.warn("[BOT][PROFILE] ❌ Ошибка очистки контекста: userId=${user.id} email=${user.email} причина=${e.message}")
                sender.editText(chatId, msgId, "❌ Ошибка: ${e.message}",
                    keyboard(row(btn("◀️ Назад", "menu:profile"))))
            }
    }


    fun showUnlinkConfirm(chatId: Long, msgId: Int) {
        log.info("[BOT][PROFILE] Запрос отвязки Telegram: chatId=$chatId")
        sender.editText(
            chatId, msgId,
            "⚠️ *Отвязать Telegram?*\n\n" +
                    "После отвязки:\n" +
                    "• Мониторинг лидов остановится\n" +
                    "• Уведомления перестанут приходить\n\n" +
                    "Аккаунт getaimly\\.io сохранится\\. Вы сможете привязать снова.",
            keyboard(row(
                btn("✅ Да, отвязать", "profile:unlink_tg:confirm"),
                btn("❌ Отмена",        "menu:profile"),
            )),
            parseMarkdown = true,
        )
    }

    fun confirmUnlink(chatId: Long, msgId: Int, tgUserId: Long) {
        val user = userRepository.findByTelegramId(tgUserId).orElse(null)
        if (user == null) {
            log.warn("[BOT][PROFILE] Не авторизован при подтверждении отвязки: chatId=$chatId tgId=$tgUserId")
            sender.editText(chatId, msgId, "Нужно войти. /start")
            return
        }

        log.info("[BOT][PROFILE] Подтверждение отвязки Telegram: userId=${user.id} email=${user.email} tgId=$tgUserId")

        runCatching { authService.unlinkTelegram(user.id) }
            .onSuccess {
                log.info("[BOT][PROFILE] ✅ Telegram отвязан: userId=${user.id} email=${user.email}")
                sender.editText(
                    chatId, msgId,
                    "✅ Telegram отвязан.\n\nЧтобы снова привязать — зайдите в личный кабинет:\n" +
                            "🌐 ${BotAuthHandler.SITE_DASHBOARD} → Профиль",
                )
            }
            .onFailure { e ->
                log.warn("[BOT][PROFILE] ❌ Ошибка отвязки Telegram: userId=${user.id} email=${user.email} причина=${e.message}")
                sender.editText(chatId, msgId, "❌ Ошибка: ${e.message}",
                    keyboard(row(btn("◀️ Назад", "menu:profile"))))
            }
    }
}