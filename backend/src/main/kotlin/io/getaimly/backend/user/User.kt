package io.getaimly.backend.user

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = true)
    var password: String? = null,

    @Column(name = "first_name")
    var firstName: String? = null,

    // telegram
    @Column(name = "telegram_id", unique = true)
    var telegramId: Long? = null,

    @Column(name = "telegram_username")
    var telegramUsername: String? = null,

    @Column(name = "telegram_linked_at")
    var telegramLinkedAt: LocalDateTime? = null,

    // google oauth
    @Column(name = "google_id", unique = true)
    var googleId: String? = null,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: Role = Role.USER,


    @Column(name = "subscription_status")
    var subscriptionStatus: String? = null,

    @Column(name = "subscription_plan")
    var subscriptionPlan: String? = null,

    @Column(name = "leads_count", nullable = false)
    var leadsCount: Int = 0,

    @Column(name = "business_context", columnDefinition = "TEXT")
    var businessContext: String? = null,

    @Column(name = "respond_to_service_offers", nullable = false)
    var respondToServiceOffers: Boolean = false,

    @Column(name = "created_at", nullable = true, updatable = false)
    val createdAt: LocalDateTime? = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = true)
    var updatedAt: LocalDateTime? = LocalDateTime.now(),
)

enum class Role { USER, ADMIN }