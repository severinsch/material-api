import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private enum class ParameterType {Query, Path}

/**
 * Extracts and parses a UUID from the specified path parameter in the incoming HTTP call.
 * If the parameter is invalid or missing and required, responds with a 400 Bad Request status.
 *
 * @param call The incoming HTTP routing call from which the parameter is extracted.
 * @param parameterName The name of the parameter to extract the UUID from. Defaults to "id".
 * @param required Whether the parameter is required. For required parameters a Bad Request response is sent if it is missing.
 * @return The parsed UUID if valid; null if the parameter is missing or invalid.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun getUUIDfromPathParameter(call: RoutingCall, parameterName: String = "id", required: Boolean = true): Uuid? {
    return getUUIDfromParameter(ParameterType.Path, call, parameterName, required)
}

/**
 * Extracts and parses a UUID from the specified query parameter in the incoming HTTP call.
 * If the parameter is invalid or missing and required, responds with a 400 Bad Request status.
 *
 * @param call The incoming HTTP routing call from which the parameter is extracted.
 * @param parameterName The name of the parameter to extract the UUID from. Defaults to "id".
 * @param required Whether the parameter is required. For required parameters a Bad Request response is sent if it is missing.
 * @return The parsed UUID if valid; null if the parameter is missing or invalid.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun getUUIDfromQueryParameter(call: RoutingCall, parameterName: String = "id", required: Boolean = false): Uuid? {
    return getUUIDfromParameter(ParameterType.Query, call, parameterName, required)
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun getUUIDfromParameter(type: ParameterType, call: RoutingCall, parameterName: String = "id", required: Boolean = false): Uuid? {
    val params = when(type) {
        ParameterType.Query -> call.request.queryParameters
        ParameterType.Path -> call.parameters
    }
    if (!params.contains(parameterName)) {
        if (required) {
            call.respond(HttpStatusCode.BadRequest)
        }
        return null
    }
    val id = params[parameterName]!!
    var uuid: Uuid?
    try {
        uuid = Uuid.parse(id)
    } catch (_: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest)
        return null
    }
    return uuid
}