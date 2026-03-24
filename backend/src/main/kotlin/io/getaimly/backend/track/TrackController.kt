package io.getaimly.backend.track

import io.getaimly.backend.user.User
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class TrackRequest(
    val event: String,
    val props: Map<String, Any?> = emptyMap(),
    val page:  String?           = null,
)

@RestController
@RequestMapping("/api/v1/track")
class TrackController {

    private val log = LoggerFactory.getLogger(TrackController::class.java)

    @PostMapping
    fun track(
        @AuthenticationPrincipal user: User,
        @RequestBody req: TrackRequest,
    ): ResponseEntity<Void> {
        if (req.event.isBlank()) return ResponseEntity.badRequest().build()

        val pageStr  = if (!req.page.isNullOrBlank()) " page=\"${req.page}\"" else ""
        val propsStr = if (req.props.isNotEmpty()) " props=${req.props}" else ""

        log.info("[FE] ${req.event}: userId=${user.id} email=${user.email}$pageStr$propsStr")

        return ResponseEntity.ok().build()
    }
}