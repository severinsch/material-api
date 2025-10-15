package routes

import getUUIDfromQueryParameter
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import services.DamageService
import kotlin.uuid.ExperimentalUuidApi

@Serializable
data class DamageReport(
    val id: String,
    val inventoryItemId: String,
    val description: String,
    val isResolved: Boolean,
    val reportedAt: String
)

@Serializable
data class NewDamageReport(
    val inventoryItemId: String,
    val description: String,
    val isResolved: Boolean = false
)

@OptIn(ExperimentalUuidApi::class)
fun Route.damageRoutes(damageService: DamageService) {
    route("/damages") {
        /**
         * Get all Damages
         *
         * @query item_id: The id of the item to get damages for. Get all Damages if not provided.
         * @response 200 [DamageReport] List of damages.
         * @response 400 If the item_id parameter is malformed.
         */
        get {
            val uuid = getUUIDfromQueryParameter(call, "item_id", required = false)
            if (uuid != null) {
                call.respond(damageService.getByItemId(uuid))
                return@get
            }
            call.respond(damageService.getAll())
        }
        post {
            val newDamageReport = call.receive<NewDamageReport>()
            val createdDamageReport = damageService.create(newDamageReport)
            call.respond(HttpStatusCode.Created, createdDamageReport)
        }
    }
}
