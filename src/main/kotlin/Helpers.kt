import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private enum class ParameterType { Query, Path }

/**
 * Extracts and parses a UUID from the specified path parameter in the incoming HTTP call.
 * If the parameter is invalid or missing and required, responds with a 400 Bad Request status.
 *
 * @param call The incoming HTTP routing call from which the parameter is extracted.
 * @param paramName The name of the parameter to extract the UUID from. Defaults to "id".
 * @param required Whether the parameter is required. For required parameters a Bad Request response is sent if it is missing.
 * @return The parsed UUID if valid; null if the parameter is missing or invalid.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun getUUIDfromPathParameter(call: RoutingCall, paramName: String = "id", required: Boolean = true): Uuid? {
    return getUUIDfromParameter(ParameterType.Path, call, paramName, required)
}

/**
 * Extracts and parses a UUID from the specified query parameter in the incoming HTTP call.
 * If the parameter is invalid or missing and required, responds with a 400 Bad Request status.
 *
 * @param call The incoming HTTP routing call from which the parameter is extracted.
 * @param paramName The name of the parameter to extract the UUID from. Defaults to "id".
 * @param required Whether the parameter is required. For required parameters a Bad Request response is sent if it is missing.
 * @return The parsed UUID if valid; null if the parameter is missing or invalid.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun getUUIDfromQueryParameter(call: RoutingCall, paramName: String = "id", required: Boolean = false): Uuid? {
    return getUUIDfromParameter(ParameterType.Query, call, paramName, required)
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun getUUIDfromParameter(
    type: ParameterType,
    call: RoutingCall,
    paramName: String = "id",
    required: Boolean = false
): Uuid? {
    val params = when (type) {
        ParameterType.Query -> call.request.queryParameters
        ParameterType.Path -> call.parameters
    }
    if (!params.contains(paramName)) {
        if (required) {
            call.respond(HttpStatusCode.BadRequest)
        }
        return null
    }
    val id = params[paramName]!!
    var uuid: Uuid?
    try {
        uuid = Uuid.parse(id)
    } catch (_: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest)
        return null
    }
    return uuid
}