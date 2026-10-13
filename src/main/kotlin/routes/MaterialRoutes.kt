package routes

import api.*
import database.MaterialTrackingType
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.ItemService
import services.MaterialService

fun Route.materialRoutes(materialService: MaterialService, itemService: ItemService) {
    route("/materials") {
        /**
         * Get all materials.
         *
         * Query: type [database.MaterialTrackingType] Only return materials of this type.
         */
        get {
            call.respond(materialService.getAll(call.enumQueryParameter<MaterialTrackingType>("type")))
        }

        /**
         * Get a material.
         *
         * Path: id [kotlin.uuid.Uuid] The material id.
         * Response: 404 application/json [api.ErrorResponse] No material with this id.
         */
        get("/{id}") {
            call.respond(materialService.get(call.uuidPathParameter()))
        }

        /**
         * Get the items of an individual material.
         *
         * Path: id [kotlin.uuid.Uuid] The material id.
         */
        get("/{id}/items") {
            call.respond(itemService.getByMaterial(call.uuidPathParameter()))
        }

        /**
         * Create a bulk material.
         */
        post("/bulk") {
            call.respond(HttpStatusCode.Created, materialService.create(call.receive<NewBulkMaterial>()))
        }

        /**
         * Create an individual material.
         */
        post("/individual") {
            call.respond(HttpStatusCode.Created, materialService.create(call.receive<NewIndividualMaterial>()))
        }

        /**
         * Replace a bulk material.
         *
         * Path: id [kotlin.uuid.Uuid] The material id.
         * Response: 404 application/json [api.ErrorResponse] No bulk material with this id.
         */
        put("/bulk/{id}") {
            val id = call.uuidPathParameter()
            call.respond(materialService.update(id, call.receive<NewBulkMaterial>()))
        }

        /**
         * Replace an individual material.
         *
         * Path: id [kotlin.uuid.Uuid] The material id.
         * Response: 404 application/json [api.ErrorResponse] No individual material with this id.
         */
        put("/individual/{id}") {
            val id = call.uuidPathParameter()
            call.respond(materialService.update(id, call.receive<NewIndividualMaterial>()))
        }

        /**
         * Delete a material.
         *
         * Only possible while nothing references it (items, purchases, kits, loans).
         *
         * Path: id [kotlin.uuid.Uuid] The material id.
         * Response: 404 application/json [api.ErrorResponse] No material with this id.
         * Response: 409 application/json [api.ErrorResponse] The material is still referenced.
         */
        delete("/{id}") {
            materialService.delete(call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/items") {
        /**
         * Get an individual item.
         *
         * Path: id [kotlin.uuid.Uuid] The item id.
         * Response: 404 application/json [api.ErrorResponse] No item with this id.
         */
        get("/{id}") {
            call.respond(itemService.get(call.uuidPathParameter()))
        }

        /**
         * Create an individual item.
         *
         * Response: 409 application/json [api.ErrorResponse] The material doesn't exist or isn't an individual material, or the identifier is taken.
         */
        post {
            call.respond(HttpStatusCode.Created, itemService.create(call.receive<NewIndividualItem>()))
        }

        /**
         * Replace an individual item.
         *
         * Path: id [kotlin.uuid.Uuid] The item id.
         * Response: 404 application/json [api.ErrorResponse] No item with this id.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(itemService.update(id, call.receive<NewIndividualItem>()))
        }

        /**
         * Delete an individual item.
         *
         * Only possible while it has no damage reports or loans; retire it via its status otherwise.
         *
         * Path: id [kotlin.uuid.Uuid] The item id.
         * Response: 404 application/json [api.ErrorResponse] No item with this id.
         * Response: 409 application/json [api.ErrorResponse] The item is still referenced.
         */
        delete("/{id}") {
            itemService.delete(call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
