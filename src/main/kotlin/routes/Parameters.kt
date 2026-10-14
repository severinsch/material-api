package routes

import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid

/** The UUID path parameter [name]. Responds with 400 if it's malformed. */
fun RoutingCall.uuidPathParameter(name: String = "id"): Uuid =
    parseUuid(name, parameters[name] ?: throw BadRequestException("Der Pfadparameter $name fehlt."))

/** The optional UUID query parameter [name]. Responds with 400 if it's malformed. */
fun RoutingCall.uuidQueryParameter(name: String): Uuid? = queryParameter(name)?.let { parseUuid(name, it) }

/** The optional boolean query parameter [name]. Responds with 400 if it's malformed. */
fun RoutingCall.booleanQueryParameter(name: String): Boolean? = queryParameter(name)?.let {
    it.toBooleanStrictOrNull() ?: throw BadRequestException("Der Parameter $name muss true oder false sein.")
}

/** The optional enum query parameter [name], case-insensitive. Responds with 400 if it's not a valid value. */
inline fun <reified T : Enum<T>> RoutingCall.enumQueryParameter(name: String): T? = queryParameter(name)?.let { value ->
    enumValues<T>().find { it.name.equals(value, ignoreCase = true) }
        ?: throw BadRequestException("Der Parameter $name muss einer dieser Werte sein: ${enumValues<T>().joinToString()}.")
}

/**
 * Reads query parameters via `request` on purpose: Ktor's OpenAPI inference recognizes `call.queryParameters[...]`
 * and would add an untyped duplicate of the parameters documented in the route comments.
 */
@PublishedApi
internal fun RoutingCall.queryParameter(name: String): String? = request.queryParameters[name]

private fun parseUuid(name: String, value: String): Uuid =
    Uuid.parseOrNull(value) ?: throw BadRequestException("Der Parameter $name ist keine gültige ID.")
