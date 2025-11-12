package routes

import database.MaterialTrackingType
import getUUIDfromPathParameter
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.material.ItemService
import services.material.MaterialService

fun Route.materialRoutes(materialService: MaterialService, itemService: ItemService) {
    route("/materials") {
        /**
         * Get all Materials
         *
         * @query type The [MaterialTrackingType] of the materials to get.
         * @response 200 [MaterialResponse]+ A list of Materials
         * @response 400 If the type parameter is malformed.
         */
        get {
            val qParams = call.request.queryParameters
            if (qParams.isEmpty()) {
                call.respond(materialService.getAll())
                return@get
            }
            when (qParams["type"]?.lowercase()) {
                "bulk" -> call.respond(materialService.getByType(MaterialTrackingType.BULK))
                "individual" -> call.respond(materialService.getByType(MaterialTrackingType.INDIVIDUAL))
                else -> call.respond(HttpStatusCode.BadRequest)
            }
        }

        /**
         * Get Material by id
         *
         * @path id The id of the material to get
         *   format: uuid
         * @response 200 [MaterialResponse] The returned Material
         * @response 400 If the id parameter is malformed.
         * @response 404 If no Material was found for the specified ID
         */
        get("/{id}") {
            val uuid = getUUIDfromPathParameter(call) ?: return@get
            val material = materialService.getById(uuid) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, material)
        }

        /**
         * Get Items for Material by id
         *
         * @path id The id of the material to get items for
         *   format: uuid
         * @response 200 [IndividualItemResponse]+ A list of Items
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

        /**
         * Create new Bulk Material
         *
         * @response 201 [BulkMaterialResponse] The newly created bulk Material
         */
        post("/bulk") {
            val newBulkMaterial = call.receive<NewBulkMaterial>()
            val createdMaterial = materialService.create(newBulkMaterial)
            call.respond(HttpStatusCode.Created, createdMaterial)
        }

        /**
         * Create new Individual Material
         *
         * @response 201 [IndividualMaterialSummaryResponse] The newly created individual Material
         */
        post("/individual") {
            val newIndividualMaterial = call.receive<NewIndividualMaterial>()
            val createdMaterial = materialService.create(newIndividualMaterial)
            call.respond(HttpStatusCode.Created, createdMaterial)
        }

        /**
         * Update Bulk Material
         *
         * @response 200 [BulkMaterialResponse] The updated bulk Material
         * @path id The id of the material to update
         *   format: uuid
         */
        put("/bulk/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@put call.respond(HttpStatusCode.BadRequest)
            val newBulkMaterial = call.receive<NewBulkMaterial>()
            val updatedMaterial = materialService
                .update(id, newBulkMaterial) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, updatedMaterial)
        }

        /**
         * Update Individual Material
         *
         * @response 200 [IndividualMaterialSummaryResponse] The updated individual Material
         * @path id The id of the material to update
         *   format: uuid
         */
        put("/individual/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@put call.respond(HttpStatusCode.BadRequest)
            val newIndividualMaterial = call.receive<NewIndividualMaterial>()
            val updatedMaterial = materialService
                .update(id, newIndividualMaterial) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, updatedMaterial)
        }

        /**
         * Delete Material
         *
         * @response 200
         * @path id The id of the material to delete
         *   format: uuid
         */
        delete("/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val success = materialService.delete(id)
            if (success) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
    route("/items") {

        /**
         * Get individual item by id
         *
         * @path id The id of the item to get
         *   format: uuid
         * @response 200 [IndividualItemResponse] The returned Individual item
         * @response 400 If the id parameter is malformed.
         * @response 404 If no Item was found for the specified ID
         */
        get("/{id}") {
            val uuid = getUUIDfromPathParameter(call) ?: return@get
            val item = itemService.getById(uuid) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, item)
        }

        /**
         * Create a new individual item
         *
         * @response 201 [IndividualItemResponse] The newly created individual item
         * @response 400 If the item could not be created
         */
        post {
            val newItem = call.receive<NewIndividualItem>()
            val createdItem = itemService.create(newItem) ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.Created, createdItem)
        }

        /**
         * Update individual item
         *
         * @response 200 [IndividualItemResponse] The updated individual item
         * @path id The id of the item to update
         *   format: uuid
         */
        put("/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@put call.respond(HttpStatusCode.BadRequest)
            val newItem = call.receive<NewIndividualItem>()
            val updatedItem = itemService.update(id, newItem) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, updatedItem)
        }

        /**
         * Delete individual item
         *
         * @response 200
         * @path id The id of the item do delete
         *   format: uuid
         */
        delete("/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val success = itemService.delete(id)
            if (success) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
