package io.getaimly.backend.user

import io.getaimly.backend.auth.NotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class BalanceResponse(val balance: Int)

@RestController
@RequestMapping("/api/v1/user")
class BalanceController(
    private val userRepository: UserRepository,
) {

    @GetMapping("/balance")
    fun getBalance(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<BalanceResponse> =
        ResponseEntity.ok(BalanceResponse(user.balance))
}