package io.getaimly.backend.config

import io.getaimly.backend.user.UserRepository
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import javax.sql.DataSource


@RestController
@RequestMapping("/api/v1/health")
class HealthController(
    private val dataSource:     DataSource,
    private val userRepository: UserRepository,
) {

    @GetMapping
    fun health(): ResponseEntity<HealthResponse> {
        val dbStatus = checkDatabase()
        val overall  = if (dbStatus == "UP") "UP" else "DEGRADED"

        return ResponseEntity.ok(
            HealthResponse(
                status    = overall,
                timestamp = LocalDateTime.now().toString(),
                version   = "1.0.0",
                checks    = mapOf(
                    "database" to CheckResult(
                        status  = dbStatus,
                        details = if (dbStatus == "UP") "PostgreSQL connected" else "Connection failed",
                    ),
                    "api" to CheckResult(
                        status  = "UP",
                        details = "Spring Boot running",
                    ),
                ),
            )
        )
    }


    @GetMapping("/details")
    fun details(): ResponseEntity<DetailsResponse> {
        val dbStatus    = checkDatabase()
        val userCount   = runCatching { userRepository.count() }.getOrDefault(-1L)

        return ResponseEntity.ok(
            DetailsResponse(
                status      = if (dbStatus == "UP") "UP" else "DEGRADED",
                timestamp   = LocalDateTime.now().toString(),
                database    = dbStatus,
                userCount   = userCount,
                javaVersion = System.getProperty("java.version"),
                memory      = MemoryInfo(
                    totalMb = Runtime.getRuntime().totalMemory() / 1024 / 1024,
                    freeMb  = Runtime.getRuntime().freeMemory()  / 1024 / 1024,
                    maxMb   = Runtime.getRuntime().maxMemory()   / 1024 / 1024,
                ),
            )
        )
    }

    private fun checkDatabase(): String {
        return try {
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SELECT 1")
                }
            }
            "UP"
        } catch (e: Exception) {
            "DOWN"
        }
    }
}

data class HealthResponse(
    val status:    String,
    val timestamp: String,
    val version:   String,
    val checks:    Map<String, CheckResult>,
)

data class CheckResult(
    val status:  String,
    val details: String,
)

data class DetailsResponse(
    val status:      String,
    val timestamp:   String,
    val database:    String,
    val userCount:   Long,
    val javaVersion: String,
    val memory:      MemoryInfo,
)

data class MemoryInfo(
    val totalMb: Long,
    val freeMb:  Long,
    val maxMb:   Long,
)


class DatabaseHealthIndicator(
    private val dataSource: DataSource,
) : HealthIndicator {

    override fun health(): Health {
        return try {
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("SELECT 1") }
            }
            Health.up()
                .withDetail("database", "PostgreSQL")
                .withDetail("status", "Connected")
                .build()
        } catch (e: Exception) {
            Health.down()
                .withDetail("error", e.message)
                .build()
        }
    }
}