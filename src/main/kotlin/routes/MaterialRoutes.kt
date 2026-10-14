package routes

import api.MaterialTrackingType
import api.NewIndividualItem
import api.NewMaterial
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.ItemService
import services.MaterialService

fun Route.materialRoutes(materialService: MaterialService) {
    route("/materials") {
        /**
         * Get all materials.
         *
         * Query: type [api.MaterialTrackingType] Only return materials of this type.
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
         * Create a material. `trackingType` decides whether it's a bulk or an individual material.
         *
         * Response: 400 application/json [api.ErrorResponse] Invalid material or unknown location.
         */
        post {
            call.respond(HttpStatusCode.Created, materialService.create(call.receive<NewMaterial>()))
        }

        /**
         * Replace a material. Its `trackingType` can't be changed.
         *
         * Path: id [kotlin.uuid.Uuid] The material id.
         * Response: 400 application/json [api.ErrorResponse] Invalid material or unknown location.
         * Response: 404 application/json [api.ErrorResponse] No material with this id.
         * Response: 409 application/json [api.ErrorResponse] The tracking type differs, the name is taken, or the stock
         * differs from `expectedAvailableCount` or would drop below the pieces lent out.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(materialService.update(id, call.receive<NewMaterial>()))
        }

        /**
         * Delete a material. Only possible while nothing references it (items, purchases, kits, loans).
         *
         * Path: id [kotlin.uuid.Uuid] The material id.
         * Response: 404 application/json [api.ErrorResponse] No material with this id.
         * Response: 409 application/json [api.ErrorResponse] The material is still in use.
         */
        delete("/{id}") {
            materialService.delete(call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

fun Route.itemRoutes(itemService: ItemService) {
    route("/items") {
        /**
         * Get all individual items.
         *
         * Query: materialId [kotlin.uuid.Uuid] Only return items of this material.
         */
        get {
            call.respond(itemService.getAll(call.uuidQueryParameter("materialId")))
        }

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
         * Response: 400 application/json [api.ErrorResponse] Invalid item, or the material isn't an individual one.
         * Response: 409 application/json [api.ErrorResponse] The identifier is taken.
         */
        post {
            call.respond(HttpStatusCode.Created, itemService.create(call.receive<NewIndividualItem>()))
        }

        /**
         * Create several individual items at once, e.g. a set of new tents. At most 100; either all of them are
         * created or none.
         *
         * Response: 400 application/json [api.ErrorResponse] Invalid or no items, or a material isn't an individual one.
         * Response: 409 application/json [api.ErrorResponse] An identifier is taken or used twice.
         */
        post("/batch") {
            call.respond(HttpStatusCode.Created, itemService.createAll(call.receive<List<NewIndividualItem>>()))
        }

        /**
         * Replace an individual item. Its `materialId` can't be changed.
         *
         * Path: id [kotlin.uuid.Uuid] The item id.
         * Response: 400 application/json [api.ErrorResponse] Invalid item.
         * Response: 404 application/json [api.ErrorResponse] No item with this id.
         * Response: 409 application/json [api.ErrorResponse] The identifier is taken.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(itemService.update(id, call.receive<NewIndividualItem>()))
        }

        /**
         * Delete an individual item. Only possible while it has no damage reports or loans; retire it via its status
         * otherwise.
         *
         * Path: id [kotlin.uuid.Uuid] The item id.
         * Response: 404 application/json [api.ErrorResponse] No item with this id.
         * Response: 409 application/json [api.ErrorResponse] The item is still in use.
         */
        delete("/{id}") {
            itemService.delete(call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
