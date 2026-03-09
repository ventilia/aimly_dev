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
    val id: Long,
    val chatTitle: String,
    val chatLink: String,
    val authorName: String,
    val authorUsername: String,
    val messageText: String,
    val messageLink: String,
    val matchedKeyword: String,
    val status: String,
    val foundAt: String,
    val aiValid: Boolean?,
    val aiReason: String?,
    val contextMessages: List<String>,
)

data class LeadPageDto(
    val content: List<LeadDto>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int,
    val newCount: Long,
)

data class ChatSubscriptionDto(
    val id: Long,
    val chatLink: String,
    val chatTitle: String,
    val chatTgId: Long,
    val isActive: Boolean,
    val createdAt: String,
)

data class KeywordDto(
    val id: Long,
    val keyword: String,
    val isActive: Boolean,
    val variants: List<String> = emptyList(),
)


data class BusinessContextDto(
    val businessContext: String?,
)


@Service
class LeadService(
    private val leadRepo: LeadRepository,
    private val subscriptionRepo: ChatSubscriptionRepository,
    private val keywordRepo: KeywordRepository,
    private val userRepo: UserRepository,

    @Lazy private val bot: AimlyBot,
    private val aiService: AiService,
    @Value("\${userbot.url:http://localhost:9090}") private val userbotUrl: String,
    @Value("\${internal.api-secret:aimly_internal_secret_change_in_prod}") private val internalSecret: String,
) {
    private val log = LoggerFactory.getLogger(LeadService::class.java)


    private val AI_PLANS = setOf("START", "BUSINESS")

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
        lead.status = runCatching { LeadStatus.valueOf(status.uppercase()) }
            .getOrElse { throw IllegalArgumentException("неверный статус: $status") }
        return leadRepo.save(lead).toDto()
    }



    @Transactional
    fun processIncomingMessage(req: IncomingMessageRequest) {
        val user = userRepo.findById(req.userId).orElse(null) ?: run {
            log.warn("пользователь ${req.userId} не найден — сообщение проигнорировано")
            return
        }

        if (user.subscriptionStatus !in setOf("ACTIVE", "TRIAL")) {
            log.warn("пользователь ${user.email} не имеет активной подписки — лид проигнорирован")
            return
        }


        if (leadRepo.existsByTgMessageIdAndTgChatIdAndUserId(req.tgMessageId, req.chatTgId, req.userId)) {
            log.debug("дубль: messageId=${req.tgMessageId} chatId=${req.chatTgId} userId=${req.userId}")
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
        )
        val saved = leadRepo.save(lead)
        user.leadsCount = user.leadsCount + 1
        userRepo.save(user)

        log.info("лид #${saved.id} userId=${user.id} keyword=${req.matchedKeyword} chat=${req.chatTitle} aiEnabled=$hasAiPlan")

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


            val businessContext = user.businessContext

            aiService.validateAsync(
                messageText     = req.messageText,
                keyword         = req.matchedKeyword,
                contextMessages = req.contextMessages.take(3),
                recentLeads     = recentLeads,
                businessContext = businessContext,
            ) { result ->
                if (result != null) {
                    runCatching {
                        leadRepo.findById(saved.id).ifPresent { l ->
                            l.aiValid  = result.valid
                            l.aiReason = result.reason
                            if (result.valid == false) {
                                l.status = LeadStatus.IGNORED
                                log.info("лид #${l.id} автоматически архивирован (AI отклонил): ${result.reason}")
                            } else {
                                log.debug("ai: лид #${l.id} valid=${result.valid} reason=${result.reason}")
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
                                telegramChatId  = tgId,
                                chatTitle       = req.chatTitle,
                                text            = req.messageText.take(200),
                                link            = req.messageLink,
                                keyword         = req.matchedKeyword,
                                authorUsername  = req.authorUsername,
                                authorName      = req.authorName,
                            )
                        }.onFailure { log.warn("ошибка telegram уведомления: ${it.message}") }
                    }
                }
            }
        } else {

            user.telegramId?.let { tgId ->
                runCatching {
                    bot.notifyNewLead(
                        telegramChatId  = tgId,
                        chatTitle       = req.chatTitle,
                        text            = req.messageText.take(200),
                        link            = req.messageLink,
                        keyword         = req.matchedKeyword,

                        authorUsername  = req.authorUsername,
                        authorName      = req.authorName,
                    )
                }.onFailure { log.warn("ошибка telegram уведомления: ${it.message}") }
            }
        }
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

        val inactiveExisting = subscriptionRepo.findByUserIdAndChatLink(user.id, normalized)
        return if (inactiveExisting != null) {
            inactiveExisting.isActive = true
            val saved = subscriptionRepo.save(inactiveExisting)
            val tgInfo = notifyUserbotSubscribeSync(user, saved, getKeywords(user).map { it.keyword })
            if (tgInfo != null && tgInfo.chatTgId != 0L) {
                saved.chatTgId  = tgInfo.chatTgId
                saved.chatTitle = tgInfo.chatTitle.ifBlank { saved.chatTitle }
                subscriptionRepo.save(saved)
            }
            saved.toDto()
        } else {
            val sub = ChatSubscription(user = user, chatLink = normalized)
            val saved = subscriptionRepo.save(sub)
            val tgInfo = notifyUserbotSubscribeSync(user, saved, getKeywords(user).map { it.keyword })
            if (tgInfo != null && tgInfo.chatTgId != 0L) {
                saved.chatTgId  = tgInfo.chatTgId
                saved.chatTitle = tgInfo.chatTitle.ifBlank { saved.chatTitle }
                subscriptionRepo.save(saved)
            }
            saved.toDto()
        }
    }

    @Transactional
    fun removeSubscription(user: User, subscriptionId: Long) {
        val sub = subscriptionRepo.findById(subscriptionId)
            .orElseThrow { NoSuchElementException("подписка не найдена") }
        if (sub.user.id != user.id) throw SecurityException("нет доступа")
        sub.isActive = false
        subscriptionRepo.save(sub)
        if (sub.chatTgId != 0L) {
            val remaining = subscriptionRepo.findByChatTgId(sub.chatTgId)
            if (remaining.isEmpty()) {
                notifyUserbotLeaveChat(sub.chatTgId)
            }
        }
        notifyUserbotUnsubscribe(user, sub.chatLink)
    }



    fun getKeywords(user: User): List<KeywordDto> =
        keywordRepo.findByUserIdAndIsActiveTrue(user.id).map { it.toDto() }


    @Transactional
    fun addKeyword(user: User, keyword: String): KeywordDto {
        val trimmed = keyword.trim().lowercase()
        if (trimmed.isBlank()) throw IllegalArgumentException("ключевое слово не может быть пустым")
        if (trimmed.length > 100) throw IllegalArgumentException("слишком длинное ключевое слово")

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

        val hasAiPlan = user.subscriptionPlan in AI_PLANS
        if (hasAiPlan) {
            val allVariants = aiService.expandKeyword(trimmed)
            val variantsOnly = allVariants.filter { it != trimmed }
            if (variantsOnly.isNotEmpty()) {
                kw.variants = variantsOnly.joinToString(",")
                keywordRepo.save(kw)
            }
        }

        syncKeywordsToUserbot(user)
        return kw.toDto()
    }

    @Transactional
    fun removeKeyword(user: User, keywordId: Long) {
        val kw = keywordRepo.findById(keywordId).orElseThrow { NoSuchElementException("ключевое слово не найдено") }
        if (kw.user.id != user.id) throw SecurityException("нет доступа")
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
        return BusinessContextDto(businessContext = u.businessContext)
    }


    private data class UserbotSubscribeResponse(
        val chatTgId: Long = 0,
        val chatTitle: String = "",
    )

    private fun notifyUserbotSubscribeSync(
        user: User,
        sub: ChatSubscription,
        keywords: List<String>,
    ): UserbotSubscribeResponse? {
        return try {
            val h = httpHeaders()
            val resp = restTemplate.postForEntity(
                "$userbotUrl/chats/subscribe",
                HttpEntity(mapOf("userId" to user.id, "chatLink" to sub.chatLink, "keywords" to keywords), h),
                Map::class.java,
            )

            val body = resp.body
            val chatTgId = when (val v = body?.get("chatTgID")) {
                is Number -> v.toLong()
                else      -> 0L
            }
            val chatTitle = body?.get("title") as? String ?: ""

            log.info("userbot subscribe OK: userId=${user.id} chat=${sub.chatLink} chatTgId=$chatTgId title=$chatTitle")
            UserbotSubscribeResponse(chatTgId = chatTgId, chatTitle = chatTitle)

        } catch (e: Exception) {
            log.error("userbot subscribe failed (sync): userId=${user.id} chat=${sub.chatLink} — ${e.message}")
            throw RuntimeException("Не удалось подключиться к чату. Попробуйте позже. (${e.message})")
        }
    }

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
        val kwEntities = keywordRepo.findByUserIdAndIsActiveTrue(user.id)
        val originals = kwEntities.map { it.keyword }.distinct()

        val allVariants = kwEntities.flatMap { it.allVariants() }.distinct()
        val userId = user.id

        log.info(
            "userbot keywords sync: userId=$userId originals=${originals.size} total=${allVariants.size} (с вариантами)" +
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
        return LeadDto(
            id             = id,
            chatTitle      = sub?.chatTitle?.ifBlank { sub.chatLink } ?: "",
            chatLink       = sub?.chatLink ?: "",
            authorName     = authorName,
            authorUsername = authorUsername,
            messageText    = messageText,
            messageLink    = messageLink,
            matchedKeyword = matchedKeyword,
            status         = status.name,
            foundAt        = foundAt.toString(),
            aiValid        = aiValid,
            aiReason       = aiReason,
            contextMessages = ctx,
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