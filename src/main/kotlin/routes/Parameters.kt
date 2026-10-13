package routes

import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid

/** The UUID path parameter [name]. Responds with 400 if it's malformed. */
fun RoutingCall.uuidPathParameter(name: String = "id"): Uuid =
    parseUuid(name, parameters[name] ?: throw BadRequestException("Missing path parameter $name"))

/** The optional UUID query parameter [name]. Responds with 400 if it's malformed. */
fun RoutingCall.uuidQueryParameter(name: String): Uuid? = queryParameters[name]?.let { parseUuid(name, it) }

/** The optional boolean query parameter [name]. Responds with 400 if it's malformed. */
fun RoutingCall.booleanQueryParameter(name: String): Boolean? = queryParameters[name]?.let {
    it.toBooleanStrictOrNull() ?: throw BadRequestException("Query parameter $name must be true or false")
}

/** The optional enum query parameter [name], case-insensitive. Responds with 400 if it's not a valid value. */
inline fun <reified T : Enum<T>> RoutingCall.enumQueryParameter(name: String): T? = queryParameters[name]?.let { value ->
    enumValues<T>().find { it.name.equals(value, ignoreCase = true) }
        ?: throw BadRequestException("Query parameter $name must be one of ${enumValues<T>().joinToString()}")
}

private fun parseUuid(name: String, value: String): Uuid =
    Uuid.parseOrNull(value) ?: throw BadRequestException("Parameter $name is not a valid UUID")
