package io.getaimly.backend.lead

import io.getaimly.backend.ai.AiService
import io.getaimly.backend.bot.AimlyBot
import io.getaimly.backend.user.User
import io.getaimly.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestTemplate
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


data class LeadDto(
    val id:              Long,
    val chatTitle:       String,
    val chatLink:        String,
    val authorName:      String,
    val authorUsername:  String,
    val messageText:     String,
    val messageLink:     String,
    val matchedKeyword:  String,
    val status:          String,
    val foundAt:         String,
    val aiValid:         Boolean?,
    val aiReason:        String?,
    val contextMessages: List<String>,
    // Источник: "LIVE" или "MANUAL_EXPORT"
    val source:          String,
    // Реальное время сообщения (для MANUAL_EXPORT — из файла, для LIVE — совпадает с foundAt)
    val messageDate:     String,
)

data class LeadPageDto(
    val content:       List<LeadDto>,
    val totalElements: Long,
    val totalPages:    Int,
    val page:          Int,
    val size:          Int,
    val newCount:      Long,
)

data class ChatSubscriptionDto(
    val id:        Long,
    val chatLink:  String,
    val chatTitle: String,
    val chatTgId:  Long,
    val isActive:  Boolean,
    val createdAt: String,
)

data class KeywordDto(
    val id:       Long,
    val keyword:  String,
    val isActive: Boolean,
    val variants: List<String> = emptyList(),
)

data class BusinessContextDto(
    val businessContext: String?,
)


@Service
class LeadService(
    private val leadRepo:         LeadRepository,
    private val subscriptionRepo: ChatSubscriptionRepository,
    private val keywordRepo:      KeywordRepository,
    private val userRepo:         UserRepository,

    @Lazy private val bot: AimlyBot,
    private val aiService: AiService,
    @Value("\${userbot.url:http://localhost:9090}") private val userbotUrl: String,
    @Value("\${internal.api-secret:aimly_internal_secret_change_in_prod}") private val internalSecret: String,
) {
    private val log = LoggerFactory.getLogger(LeadService::class.java)

    private val AI_PLANS = setOf("START", "BUSINESS", "TRIAL")

    private val restTemplate: RestTemplate = RestTemplate(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(5_000)
            setReadTimeout(30_000)
        }
    )

    private val longRestTemplate: RestTemplate = RestTemplate(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(5_000)
            setReadTimeout(3 * 60 * 1000)
        }
    )

    private val userbotExecutor: ExecutorService = Executors.newFixedThreadPool(4)



    fun getLeads(user: User, status: String?, page: Int, size: Int): LeadPageDto {
        val pageable = PageRequest.of(page, size.coerceIn(1, 100))
        val result = if (status != null) {
            val s = runCatching { LeadStatus.valueOf(status.uppercase()) }
                .getOrElse { throw IllegalArgumentException("неверный статус: $status") }
            leadRepo.findByUserIdAndStatusOrderByFoundAtDesc(user.id, s, pageable)
        } else {
            leadRepo.findByUserIdOrderByFoundAtDesc(user.id, pageable)
        }
        val newCount = leadRepo.countByUserIdAndStatus(user.id, LeadStatus.NEW)

        val isPolling = size == 1 && status?.uppercase() == "NEW"
        if (!isPolling) {
            log.info("[LEAD] Список запрошен: userId=${user.id} email=${user.email} filter=${status ?: "ALL"} page=$page size=$size")
        }

        return LeadPageDto(
            content       = result.content.map { it.toDto() },
            totalElements = result.totalElements,
            totalPages    = result.totalPages,
            page          = result.number,
            size          = result.size,
            newCount      = newCount,
        )
    }

    @Transactional
    fun updateLeadStatus(user: User, leadId: Long, status: String): LeadDto {
        val lead = leadRepo.findById(leadId).orElseThrow { NoSuchElementException("лид не найден") }
        if (lead.user.id != user.id) throw SecurityException("нет доступа")

        val oldStatus = lead.status.name
        val newStatus = runCatching { LeadStatus.valueOf(status.uppercase()) }
            .getOrElse { throw IllegalArgumentException("неверный статус: $status") }

        lead.status = newStatus
        val saved = leadRepo.save(lead)

        log.info("[LEAD] Статус изменён: userId=${user.id} email=${user.email} leadId=$leadId $oldStatus → ${newStatus.name}")
        return saved.toDto()
    }

    @Transactional
    fun markAllRead(user: User) {
        leadRepo.markAllViewedByUserId(user.id)
        log.info("[LEAD] Все прочитаны: userId=${user.id} email=${user.email}")
    }



    @Transactional
    fun processIncomingMessage(req: IncomingMessageRequest) {
        val user = userRepo.findById(req.userId).orElse(null) ?: run {
            log.warn("пользователь ${req.userId} не найден — сообщение проигнорировано")
            return
        }

        if (user.subscriptionStatus !in setOf("ACTIVE", "TRIAL")) {
            log.warn("[LEAD][WARN] Нет подписки: userId=${user.id} email=${user.email} — лид пропущен")
            return
        }

        if (leadRepo.existsByTgMessageIdAndTgChatIdAndUserId(req.tgMessageId, req.chatTgId, req.userId)) {
            log.debug("[LEAD] Дубль пропущен: messageId=${req.tgMessageId} chatId=${req.chatTgId} userId=${user.id} email=${user.email}")
            return
        }

        val sub = subscriptionRepo.findByUserIdAndChatLinkAndIsActiveTrue(req.userId, req.chatLink)

        if (sub != null && sub.chatTgId == 0L && req.chatTgId != 0L) {
            sub.chatTgId = req.chatTgId
            subscriptionRepo.save(sub)
            log.info("обновлён chatTgId для подписки: chatLink=${req.chatLink} chatTgId=${req.chatTgId}")
        }

        val hasAiPlan = user.subscriptionPlan in AI_PLANS

        val lead = Lead(
            user            = user,
            subscriptionId  = sub?.id,
            tgMessageId     = req.tgMessageId,
            tgChatId        = req.chatTgId,
            authorName      = req.authorName.sanitize(),
            authorUsername  = req.authorUsername.sanitize(),
            messageText     = req.messageText.sanitize(),
            messageLink     = req.messageLink.sanitize(),
            matchedKeyword  = req.matchedKeyword.sanitize(),
            contextMessages = req.contextMessages.take(3)
                .map { it.sanitize() }
                .joinToString("\u001F"),
            source      = req.source,
            messageDate = req.messageDate ?: LocalDateTime.now(),
        )
        val saved = leadRepo.save(lead)
        user.leadsCount = user.leadsCount + 1
        userRepo.save(user)

        log.info("[LEAD] Создан: leadId=#${saved.id} userId=${user.id} email=${user.email} keyword=\"${req.matchedKeyword}\" chat=\"${req.chatTitle}\" source=${req.source} aiEnabled=$hasAiPlan historical=${req.isHistorical}")

        if (hasAiPlan) {
            val recentLeads = leadRepo.findRecentByUserId(
                userId   = user.id,
                since    = LocalDateTime.now().minusDays(7),
                pageable = PageRequest.of(0, 10),
            ).map { l ->
                AiService.RecentLead(
                    authorUsername = l.authorUsername,
                    messageText    = l.messageText.take(100),
                    foundAt        = l.foundAt.toString(),
                )
            }

            val capturedUserId  = user.id
            val capturedEmail   = user.email
            val capturedKeyword = req.matchedKeyword
            val capturedLeadId  = saved.id
            val capturedSource  = req.source

            aiService.validateAsync(
                messageText            = req.messageText,
                keyword                = req.matchedKeyword,
                contextMessages        = req.contextMessages.take(3),
                recentLeads            = recentLeads,
                businessContext        = user.businessContext,
                respondToServiceOffers = user.respondToServiceOffers,
            ) { result ->
                if (result != null) {
                    runCatching {
                        leadRepo.findById(capturedLeadId).ifPresent { l ->
                            l.aiValid  = result.valid
                            l.aiReason = result.reason
                            if (result.valid == false) {
                                l.status = LeadStatus.IGNORED
                                log.info("[AI] Отклонён: leadId=#${l.id} userId=$capturedUserId email=$capturedEmail keyword=\"$capturedKeyword\" reason=\"${result.reason.take(150)}\"")
                            } else {
                                log.info("[AI] Принят: leadId=#${l.id} userId=$capturedUserId email=$capturedEmail keyword=\"$capturedKeyword\" reason=\"${result.reason.take(150)}\"")
                            }
                            leadRepo.save(l)
                        }
                    }.onFailure { log.warn("ошибка сохранения ai результата: ${it.message}") }
                }

                val isRelevant = result?.valid != false
                if (isRelevant) {
                    user.telegramId?.let { tgId ->
                        runCatching {
                            bot.notifyNewLead(
                                telegramChatId = tgId,
                                leadId         = saved.id,
                                chatTitle      = req.chatTitle,
                                text           = req.messageText.take(200),
                                link           = req.messageLink,
                                keyword        = req.matchedKeyword,
                                authorUsername = req.authorUsername,
                                authorName     = req.authorName,
                                source         = capturedSource,
                            )
                        }.onFailure { log.warn("ошибка telegram уведомления leadId=#${saved.id}: ${it.message}") }
                    }
                }
            }
        } else {
            user.telegramId?.let { tgId ->
                runCatching {
                    bot.notifyNewLead(
                        telegramChatId = tgId,
                        leadId         = saved.id,
                        chatTitle      = req.chatTitle,
                        text           = req.messageText.take(200),
                        link           = req.messageLink,
                        keyword        = req.matchedKeyword,
                        authorUsername = req.authorUsername,
                        authorName     = req.authorName,
                        source         = req.source,
                    )
                }.onFailure { log.warn("ошибка telegram уведомления leadId=#${saved.id}: ${it.message}") }
            }
        }
    }




    internal fun String.normalizeKeyword(): String =
        this.trim()
            .lowercase()
            .trimEnd('?', '!', '.', ',', ';', ':', '…')
            .trimStart('?', '!', '.', ',', ';', ':')
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    fun getKeywords(user: User): List<KeywordDto> =
        keywordRepo.findByUserIdAndIsActiveTrue(user.id).map { it.toDto() }

    @Transactional
    fun addKeyword(user: User, keyword: String): KeywordDto {
        val trimmed = keyword.normalizeKeyword()
        if (trimmed.isBlank()) throw IllegalArgumentException("ключевое слово не может быть пустым")
        if (trimmed.length > 100) throw IllegalArgumentException("слишком длинное ключевое слово")

        val currentCount = keywordRepo.findByUserIdAndIsActiveTrue(user.id).size
        if (currentCount >= 50) throw IllegalArgumentException("Достигнут лимит — максимум 50 ключевых слов")

        val existing = keywordRepo.findByUserIdAndKeywordAndIsActiveTrue(user.id, trimmed)
        if (existing != null) {
            throw IllegalArgumentException("Ключевое слово «$trimmed» уже добавлено")
        }

        val inactive = keywordRepo.findByUserIdAndKeyword(user.id, trimmed)
        val kw = if (inactive != null) {
            inactive.isActive = true
            inactive.variants = null
            keywordRepo.save(inactive)
        } else {
            keywordRepo.save(Keyword(user = user, keyword = trimmed))
        }

        log.info("[KEYWORDS] Добавлено: userId=${user.id} email=${user.email} keyword=\"$trimmed\" итого=${currentCount + 1}")

        val hasAiPlan = user.subscriptionPlan in AI_PLANS
        if (hasAiPlan) {
            val allVariants  = aiService.expandKeyword(trimmed)
            val variantsOnly = allVariants.filter { it != trimmed }
            if (variantsOnly.isNotEmpty()) {
                kw.variants = variantsOnly.joinToString(",")
                keywordRepo.save(kw)
                log.info("[KEYWORDS] AI-варианты: userId=${user.id} email=${user.email} keyword=\"$trimmed\" вариантов=${variantsOnly.size}")
            }
        }

        syncKeywordsToUserbot(user)
        return kw.toDto()
    }

    @Transactional
    fun removeKeyword(user: User, keywordId: Long) {
        val kw = keywordRepo.findById(keywordId).orElseThrow { NoSuchElementException("ключевое слово не найдено") }
        if (kw.user.id != user.id) throw SecurityException("нет доступа")

        log.info("[KEYWORDS] Удалено: userId=${user.id} email=${user.email} keyword=\"${kw.keyword}\"")
        keywordRepo.delete(kw)
        syncKeywordsToUserbot(user)
    }




    fun getBusinessContext(user: User): BusinessContextDto =
        BusinessContextDto(businessContext = user.businessContext)

    @Transactional
    fun saveBusinessContext(user: User, context: String): BusinessContextDto {
        val trimmed = context.trim()
        if (trimmed.length > 2000) throw IllegalArgumentException("Описание слишком длинное (макс. 2000 символов)")

        val u = userRepo.findById(user.id).orElseThrow { NoSuchElementException("пользователь не найден") }
        u.businessContext = trimmed.ifBlank { null }
        userRepo.save(u)

        if (trimmed.isNotBlank()) {
            log.info("[KEYWORDS] Бизнес-контекст обновлён: userId=${user.id} email=${user.email} длина=${trimmed.length}")
        } else {
            log.info("[KEYWORDS] Бизнес-контекст очищен: userId=${user.id} email=${user.email}")
        }

        return BusinessContextDto(businessContext = u.businessContext)
    }




    fun getSubscriptions(user: User): List<ChatSubscriptionDto> =
        subscriptionRepo.findByUserIdAndIsActiveTrue(user.id).map { it.toDto() }

    @Transactional
    fun addSubscription(user: User, chatLink: String): ChatSubscriptionDto {
        val normalized = normalizeLink(chatLink)

        val activeExisting = subscriptionRepo.findByUserIdAndChatLinkAndIsActiveTrue(user.id, normalized)
        if (activeExisting != null) {
            throw IllegalArgumentException("Вы уже подписаны на этот чат")
        }

        val saved = if (subscriptionRepo.findByUserIdAndChatLink(user.id, normalized) != null) {
            val existing = subscriptionRepo.findByUserIdAndChatLink(user.id, normalized)!!
            existing.isActive = true
            subscriptionRepo.save(existing)
        } else {
            subscriptionRepo.save(ChatSubscription(user = user, chatLink = normalized))
        }

        log.info("[CHATS] Добавлен: userId=${user.id} email=${user.email} chatLink=\"$normalized\"")

        val keywords = getKeywords(user).map { it.keyword }
        userbotExecutor.submit {
            runCatching {
                val h = httpHeaders()
                val resp = restTemplate.postForEntity(
                    "$userbotUrl/chats/subscribe",
                    HttpEntity(mapOf("userId" to user.id, "chatLink" to saved.chatLink, "keywords" to keywords), h),
                    Map::class.java,
                )
                val body    = resp.body
                val tgId    = (body?.get("chatTgID") as? Number)?.toLong() ?: 0L
                val tgTitle = body?.get("title") as? String ?: ""
                if (tgId != 0L) {
                    subscriptionRepo.findById(saved.id).ifPresent { sub ->
                        sub.chatTgId  = tgId
                        sub.chatTitle = tgTitle.ifBlank { sub.chatTitle }
                        subscriptionRepo.save(sub)
                    }
                }
                log.info("userbot subscribe OK (async): userId=${user.id} email=${user.email} chat=$normalized tgId=$tgId title=\"$tgTitle\"")
            }.onFailure {
                log.warn("userbot subscribe failed (async): userId=${user.id} email=${user.email} chat=$normalized — ${it.message}")
            }
        }

        return saved.toDto()
    }

    @Transactional
    fun removeSubscription(user: User, subscriptionId: Long) {
        val sub = subscriptionRepo.findById(subscriptionId)
            .orElseThrow { NoSuchElementException("подписка не найдена") }
        if (sub.user.id != user.id) throw SecurityException("нет доступа")

        sub.isActive = false
        subscriptionRepo.save(sub)

        log.info("[CHATS] Удалён: userId=${user.id} email=${user.email} chatLink=\"${sub.chatLink}\"")

        if (sub.chatTgId != 0L) {
            val remaining = subscriptionRepo.findByChatTgId(sub.chatTgId)
            if (remaining.isEmpty()) {
                notifyUserbotLeaveChat(sub.chatTgId)
            }
        }
        notifyUserbotUnsubscribe(user, sub.chatLink)
    }



    private data class UserbotSubscribeResponse(
        val chatTgId: Long = 0,
        val chatTitle: String = "",
    )

    private fun notifyUserbotUnsubscribe(user: User, chatLink: String) {
        userbotExecutor.submit {
            runCatching {
                val h = httpHeaders()
                restTemplate.postForEntity(
                    "$userbotUrl/chats/unsubscribe",
                    HttpEntity(mapOf("userId" to user.id, "chatLink" to chatLink), h),
                    String::class.java,
                )
                log.info("userbot unsubscribe OK: userId=${user.id} chat=$chatLink")
            }.onFailure {
                log.warn("userbot unsubscribe failed: userId=${user.id} chat=$chatLink — ${it.message}")
            }
        }
    }

    private fun notifyUserbotLeaveChat(chatTgId: Long) {
        userbotExecutor.submit {
            runCatching {
                val h = httpHeaders()
                restTemplate.postForEntity(
                    "$userbotUrl/admin/chats/leave",
                    HttpEntity(mapOf("chatTgID" to chatTgId), h),
                    String::class.java,
                )
                log.info("userbot leave chat OK: chatTgId=$chatTgId")
            }.onFailure {
                log.warn("userbot leave chat failed: chatTgId=$chatTgId — ${it.message}")
            }
        }
    }

    private fun syncKeywordsToUserbot(user: User) {
        val kwEntities  = keywordRepo.findByUserIdAndIsActiveTrue(user.id)
        val originals   = kwEntities.map { it.keyword }.distinct()
        val allVariants = kwEntities.flatMap { it.allVariants() }.distinct()
        val userId      = user.id

        log.info(
            "userbot keywords sync: userId=$userId email=${user.email} originals=${originals.size} total=${allVariants.size} (с вариантами)" +
                    "\n  → originals: [${originals.joinToString(", ") { "\"$it\"" }}]" +
                    "\n  → allVariants: [${allVariants.joinToString(", ") { "\"$it\"" }}]"
        )

        userbotExecutor.submit {
            runCatching {
                val h = httpHeaders()
                restTemplate.postForEntity(
                    "$userbotUrl/keywords/update",
                    HttpEntity(
                        mapOf(
                            "userId"      to userId,
                            "keywords"    to originals,
                            "allVariants" to allVariants,
                        ),
                        h,
                    ),
                    String::class.java,
                )
                log.info("userbot keywords sync OK: userId=$userId originals=${originals.size} variants=${allVariants.size}")
            }.onFailure {
                log.warn("userbot keywords sync failed: userId=$userId — ${it.message}")
            }
        }
    }




    private fun httpHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Internal-Secret", internalSecret)
    }

    private fun String.sanitize(): String = this.replace("\u0000", "").trim()

    private fun Lead.toDto(): LeadDto {
        val sub = subscriptionId?.let { subscriptionRepo.findById(it).orElse(null) }
        val rawCtx = contextMessages
        val ctx = if (rawCtx.isNullOrBlank()) emptyList()
        else rawCtx.split("\u001F", "\u0000").filter { it.isNotBlank() }

        // Для MANUAL_EXPORT используем реальную дату сообщения.
        // Для LIVE — messageDate совпадает с foundAt.
        val displayDate = messageDate ?: foundAt

        return LeadDto(
            id              = id,
            chatTitle       = sub?.chatTitle?.ifBlank { sub.chatLink } ?: "",
            chatLink        = sub?.chatLink ?: "",
            authorName      = authorName,
            authorUsername  = authorUsername,
            messageText     = messageText,
            messageLink     = messageLink,
            matchedKeyword  = matchedKeyword,
            status          = status.name,
            foundAt         = foundAt.toString(),
            aiValid         = aiValid,
            aiReason        = aiReason,
            contextMessages = ctx,
            source          = source.name,
            messageDate     = displayDate.toString(),
        )
    }

    private fun ChatSubscription.toDto() = ChatSubscriptionDto(
        id        = id,
        chatLink  = chatLink,
        chatTitle = chatTitle.ifBlank { chatLink },
        chatTgId  = chatTgId,
        isActive  = isActive,
        createdAt = createdAt.toString(),
    )

    private fun Keyword.toDto() = KeywordDto(
        id       = id,
        keyword  = keyword,
        isActive = isActive,
        variants = variants
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList(),
    )

    private fun normalizeLink(link: String): String {
        var l = link.trim().trimEnd('/')
        if (!l.startsWith("t.me/") && !l.startsWith("https://t.me/") && !l.startsWith("@")) {
            l = "t.me/$l"
        }
        return l
    }
}