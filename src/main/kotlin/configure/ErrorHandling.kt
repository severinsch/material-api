package configure

import api.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import services.BusyException
import services.ConflictException
import services.ForbiddenException
import services.InvalidRequestException
import services.NotFoundException
import services.TooManyRequestsException
import services.UnauthorizedException
import org.postgresql.util.PSQLException
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
        exception<UnauthorizedException> { call, e -> call.respondError(HttpStatusCode.Unauthorized, e.message) }
        exception<ForbiddenException> { call, e -> call.respondError(HttpStatusCode.Forbidden, e.message) }
        exception<NotFoundException> { call, e -> call.respondError(HttpStatusCode.NotFound, e.message) }
        exception<ConflictException> { call, e -> call.respondError(HttpStatusCode.Conflict, e.message) }
        exception<TooManyRequestsException> { call, e ->
            call.response.header(HttpHeaders.RetryAfter, e.retryAfter.inWholeSeconds + 1)
            call.respondError(HttpStatusCode.TooManyRequests, e.message)
        }
        exception<BusyException> { call, e -> call.respondError(HttpStatusCode.ServiceUnavailable, e.message) }
        // Requests without a valid session; the authentication provider only sets the status.
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respondError(status, "Du bist nicht angemeldet, oder deine Sitzung ist abgelaufen.")
        }
        // Services check the common cases themselves; the constraints in the schema are the last line of defense.
        exception<SQLException> { call, e ->
            val (status, message) = constraintViolation(e) ?: return@exception call.respondInternalError(e)
            call.respondError(status, message)
        }
        exception<Throwable> { call, e ->
            // Keep Ktor's own status codes, e.g. 415 for an unsupported content type.
            defaultExceptionStatusCode(e)?.let { call.respondError(it, e.message) } ?: call.respondInternalError(e)
        }
    }
}

// Messages for violations of named constraints in the migrations, so users learn what clashed. Other violations of
// the same kind get the generic message below. MigrationTest checks that these constraints exist.
internal val constraintMessages = mapOf(
    "materials_name_key" to "Es gibt schon ein Material mit diesem Namen.",
    "storage_locations_name_key" to "Es gibt schon einen Lagerort mit diesem Namen.",
    "kits_name_key" to "Es gibt schon ein Set mit diesem Namen.",
    "individual_items_material_id_identifier_key" to "Diese Kennung ist bei diesem Material schon vergeben.",
    "individual_items_material_id_tracking_type_fkey" to
        "Das Material hat noch Gegenstände. Lösche zuerst die Gegenstände.",
    "purchases_material_id_fkey" to "Das Material hat Einkäufe. Lösche zuerst die Einkäufe.",
    "kit_group_options_material_id_fkey" to
        "Das Material wird in einem Set verwendet. Entferne es zuerst aus dem Set.",
    "loan_lines_material_id_fkey" to "Das Material wurde schon ausgeliehen und kann nicht gelöscht werden.",
    "loan_lines_individual_item_id_fkey" to
        "Der Gegenstand wurde schon ausgeliehen. Setze stattdessen seinen Status auf „Ausgemustert“.",
    "damage_reports_individual_item_id_fkey" to
        "Der Gegenstand hat Schadensmeldungen. Setze stattdessen seinen Status auf „Ausgemustert“.",
)

// https://www.postgresql.org/docs/current/errcodes-appendix.html
private fun constraintViolation(e: SQLException): Pair<HttpStatusCode, String>? {
    val causes = e.causes()
    val sqlState = causes.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState?.ifEmpty { null } }
    val constraint = causes.filterIsInstance<PSQLException>().firstNotNullOfOrNull { it.serverErrorMessage?.constraint }
    val specific = constraintMessages[constraint]
    return when (sqlState) {
        // All foreign keys define ON DELETE, so violations on delete are always RESTRICT violations.
        "23001" -> HttpStatusCode.Conflict to (specific ?: "Der Eintrag wird noch verwendet und kann nicht gelöscht werden.")
        "23503" -> HttpStatusCode.BadRequest to "Ein angegebener Eintrag existiert nicht."
        "23505" -> HttpStatusCode.Conflict to (specific ?: "Es gibt schon einen Eintrag mit diesem Namen.")
        "23514" -> HttpStatusCode.BadRequest to "Ein Wert liegt außerhalb des erlaubten Bereichs."
        else -> null
    }
}

/** The exception and its causes; batch statements report the cause as next exception. */
private fun Throwable.causes(): Sequence<Throwable> =
    generateSequence(this) { it.cause ?: (it as? SQLException)?.nextException }

private suspend fun ApplicationCall.respondInternalError(e: Throwable) {
    application.log.error("Unhandled error for ${request.httpMethod.value} ${request.uri}", e)
    respondError(HttpStatusCode.InternalServerError, "Auf dem Server ist ein unerwarteter Fehler aufgetreten.")
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String?) =
    respond(status, ErrorResponse(message ?: status.description))
