package io.getaimly.backend.lead

import io.getaimly.backend.user.User
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/leads")
class ChatExportController(
    private val exportService: ChatExportService,
) {
    private val log = LoggerFactory.getLogger(ChatExportController::class.java)

    /**
     * POST /api/v1/leads/import-export
     *
     * multipart/form-data:
     *   file — файл экспорта Telegram Desktop (.html или .json)
     *
     * Возвращает ImportResultDto с результатами импорта.
     */
    @PostMapping(
        "/import-export",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    fun importChatExport(
        @AuthenticationPrincipal user: User,
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<*> {

        if (user.subscriptionStatus !in setOf("ACTIVE", "TRIAL")) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(mapOf("error" to "Для импорта экспорта необходима активная подписка."))
        }

        if (file.isEmpty) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "Файл пустой."))
        }

        log.info(
            "[IMPORT] Запрос: userId=${user.id} email=${user.email} " +
                    "filename=${file.originalFilename} size=${file.size}"
        )

        return try {
            val result = exportService.processExport(user, file)
            ResponseEntity.ok(result)
        } catch (e: IllegalArgumentException) {
            log.warn("[IMPORT] Ошибка валидации: userId=${user.id} — ${e.message}")
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: Exception) {
            log.error("[IMPORT] Внутренняя ошибка: userId=${user.id} — ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Ошибка обработки файла. Попробуйте ещё раз."))
        }
    }
}