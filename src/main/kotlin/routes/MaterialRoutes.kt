package routes

import database.MaterialTrackingType
import getUUIDfromPathParameter
import services.material.MaterialService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.material.ItemService
import kotlin.uuid.ExperimentalUuidApi

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

        /**
         * Get Material by id
         *
         * @path id: The id of the material to get
         * @response 200 [MaterialSummaryResponse] The returned Material
         * @response 400 If the id parameter is malformed.
         * @response 404 If no Material was found for the specified ID
         */
        get("/{id}") {
            val uuid = getUUIDfromPathParameter(call) ?: return@get
            val material = materialService.getById(uuid) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, material)
        }

        // TODO: how to specify array of IndividualItemResponse instead of single for openapi spec? same for other endpoints
        /**
         * Get Items for Material by id
         *
         * @path id: The id of the material to get items for
         * @response 200 [IndividualItemResponse] The returned individual items
         * @response 400 If the id parameter is malformed or missing.
         */
        get("/{id}/items") {
            val uuid = getUUIDfromPathParameter(call) ?: return@get
            val items = itemService.getAllOfType(uuid)
            call.respond(
                HttpStatusCode.OK,
                items
            )
        }
    }
    route("/items") {

        /**
         * Get individual item by id
         *
         * @path id: The id of the item to get
         * @response 200 [IndividualItemResponse] The returned Individual item
         * @response 400 If the id parameter is malformed.
         * @response 404 If no Item was found for the specified ID
         */
        get("/{id}") {
            val uuid = getUUIDfromPathParameter(call) ?: return@get
            val item = itemService.getById(uuid) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, item)
        }
    }
}
