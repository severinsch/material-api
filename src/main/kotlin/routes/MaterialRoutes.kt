package routes

import database.MaterialTrackingType
import services.material.MaterialService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.material.ItemService
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun Route.materialRoutes(materialService: MaterialService, itemService: ItemService) {
    route("/materials") {
        get {
            val qParams = call.request.queryParameters
            if (qParams.isEmpty()) {
                call.respond(materialService.getAll())
                return@get
            }
            if (qParams["type"]?.equals("bulk", ignoreCase = true) ?: false) {
                call.respond(materialService.getByType(MaterialTrackingType.BULK))
                return@get
            }
            if (qParams["type"]?.equals("individual", ignoreCase = true) ?: false) {
                call.respond(materialService.getByType(MaterialTrackingType.INDIVIDUAL))
                return@get
            }
        }
        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            var uuid: Uuid?
            try {
                uuid = Uuid.parse(id)
            } catch (_: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest)
            }
            val material = materialService.getById(uuid) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, material)
        }
        get("/{id}/items") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            var uuid: Uuid
            try {
                uuid = Uuid.parse(id)
            } catch (_: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest)
            }
            val items = itemService.getAllOfType(uuid)
            call.respond(
                HttpStatusCode.OK,
                items
            )
        }
    }
}
