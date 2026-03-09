package io.getaimly.backend.auth

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.allErrors
            .associate { error ->
                val field = if (error is FieldError) error.field else "general"
                field to (error.defaultMessage ?: "Ошибка валидации")
            }
        return ResponseEntity
            .badRequest()
            .body(ErrorResponse("Ошибка валидации", errors))
    }

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException) =
        ResponseEntity.badRequest().body(ErrorResponse(ex.message ?: "Ошибка запроса"))

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException) =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(ex.message ?: "Не авторизован"))

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException) =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message ?: "Доступ запрещён"))

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message ?: "Не найдено"))

    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequests(ex: TooManyRequestsException) =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ErrorResponse(ex.message ?: "Слишком много запросов"))
}

data class ErrorResponse(
    val message: String,
    val errors: Map<String, String>? = null,
)