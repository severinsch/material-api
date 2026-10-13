package configure

import api.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import services.ConflictException
import services.InvalidRequestException
import services.NotFoundException
import java.sql.SQLException

/**
 * Maps exceptions to error responses with an [ErrorResponse] body. Messages are meant for users of the API; database
 * and other internal details are only logged.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        // Malformed parameters and invalid bodies, including failed validations in the API models' init blocks.
        // Only the first line: parser messages continue with hints for developers and an echo of the input.
        exception<BadRequestException> { call, e ->
            val message = generateSequence<Throwable>(e) { it.cause }.last().message?.lineSequence()?.first()
            call.respondError(HttpStatusCode.BadRequest, message)
        }
        exception<InvalidRequestException> { call, e -> call.respondError(HttpStatusCode.BadRequest, e.message) }
        exception<NotFoundException> { call, e -> call.respondError(HttpStatusCode.NotFound, e.message) }
        exception<ConflictException> { call, e -> call.respondError(HttpStatusCode.Conflict, e.message) }
        // Services check the common cases themselves; the constraints in the schema are the last line of defense.
        exception<SQLException> { call, e ->
            val (status, message) = constraintViolation(e.sqlState()) ?: return@exception call.respondInternalError(e)
            call.respondError(status, message)
        }
        exception<Throwable> { call, e ->
            // Keep Ktor's own status codes, e.g. 415 for an unsupported content type.
            defaultExceptionStatusCode(e)?.let { call.respondError(it, e.message) } ?: call.respondInternalError(e)
        }
    }
}

// https://www.postgresql.org/docs/current/errcodes-appendix.html
private fun constraintViolation(sqlState: String?): Pair<HttpStatusCode, String>? = when (sqlState) {
    // All foreign keys define ON DELETE, so violations on delete are always RESTRICT violations.
    "23001" -> HttpStatusCode.Conflict to "This entry is still in use (e.g. by items, kits or loans) and can't be deleted."
    "23503" -> HttpStatusCode.BadRequest to "A referenced entry doesn't exist."
    "23505" -> HttpStatusCode.Conflict to "An entry with this name or identifier already exists."
    "23514" -> HttpStatusCode.BadRequest to "A value is out of the allowed range."
    else -> null
}

/** The SQL state of the first SQL exception in the chain; batch statements report the cause as next exception. */
private fun Throwable.sqlState(): String? =
    generateSequence(this) { it.cause ?: (it as? SQLException)?.nextException }
        .filterIsInstance<SQLException>()
        .firstNotNullOfOrNull { it.sqlState }

private suspend fun ApplicationCall.respondInternalError(e: Throwable) {
    application.log.error("Unhandled error for ${request.httpMethod.value} ${request.uri}", e)
    respondError(HttpStatusCode.InternalServerError, null)
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String?) =
    respond(status, ErrorResponse(message ?: status.description))
