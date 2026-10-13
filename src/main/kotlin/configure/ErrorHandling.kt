package configure

import api.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.postgresql.util.PSQLException
import services.ConflictException
import services.NotFoundException

// https://www.postgresql.org/docs/current/errcodes-appendix.html
private const val RESTRICT_VIOLATION = "23001"
private const val FOREIGN_KEY_VIOLATION = "23503"
private const val UNIQUE_VIOLATION = "23505"
private const val CHECK_VIOLATION = "23514"

/** Maps exceptions to error responses with an [ErrorResponse] body. */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        // Malformed parameters and invalid bodies, including failed validations in the API models' init blocks.
        exception<BadRequestException> { call, e ->
            call.respondError(HttpStatusCode.BadRequest, generateSequence<Throwable>(e) { it.cause }.last().message)
        }
        exception<NotFoundException> { call, e -> call.respondError(HttpStatusCode.NotFound, e.message) }
        exception<ConflictException> { call, e -> call.respondError(HttpStatusCode.Conflict, e.message) }
        // Constraints in the schema are the last line of defense, e.g. deleting a material that is still in use.
        exception<ExposedSQLException> { call, e ->
            val cause = e.cause as? PSQLException ?: throw e
            val detail = cause.serverErrorMessage?.detail ?: cause.message
            when (cause.sqlState) {
                RESTRICT_VIOLATION, FOREIGN_KEY_VIOLATION, UNIQUE_VIOLATION -> call.respondError(HttpStatusCode.Conflict, detail)
                CHECK_VIOLATION -> call.respondError(HttpStatusCode.BadRequest, detail)
                else -> throw e
            }
        }
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String?) =
    respond(status, ErrorResponse(message ?: status.description))
