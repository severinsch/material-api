import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Extracts and parses a UUID from the specified parameter in the incoming HTTP call.
 * If the parameter is missing or invalid, responds with a 400 Bad Request status.
 *
 * @param call The incoming HTTP routing call from which the parameter is extracted.
 * @param parameterName The name of the parameter to extract the UUID from. Defaults to "id".
 * @return The parsed UUID if valid; null if the parameter is missing or invalid.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun getUUIDfromCall(call: RoutingCall, parameterName: String = "id"): Uuid? {
    if (!call.parameters.contains(parameterName)) {
        call.respond(HttpStatusCode.BadRequest)
        return null
    }
    val id = call.parameters[parameterName]!!
    var uuid: Uuid?
    try {
        uuid = Uuid.parse(id)
    } catch (_: IllegalArgumentException) {
       call.respond(HttpStatusCode.BadRequest)
        return null
    }
    return uuid
}