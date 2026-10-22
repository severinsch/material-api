package routes

import api.MaterialTrackingType
import api.NewIndividualItem
import api.NewMaterial
import auth.Permission
import configure.requires
import configure.tenant
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.ItemService
import services.MaterialService

fun Route.materialRoutes(materialService: MaterialService) {
    route("/materials") {
        requires(Permission.READ) {
            /**
             * Get all materials with their variants.
             *
             * Query: type [api.MaterialTrackingType] Only return materials of this type.
             */
            get {
                call.respond(materialService.getAll(call.tenant, call.enumQueryParameter<MaterialTrackingType>("type")))
            }

            /**
             * Get a material.
             *
             * Path: id [kotlin.uuid.Uuid] The material id.
             * Response: 404 application/json [api.ErrorResponse] No material with this id.
             */
            get("/{id}") {
                call.respond(materialService.get(call.tenant, call.uuidPathParameter()))
            }
        }

        requires(Permission.EDIT) {
            /**
             * Create a material with its variants (none of them with an `id`). `trackingType` decides whether it's a bulk
             * or an individual material.
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid material or variants, or unknown location.
             * Response: 409 application/json [api.ErrorResponse] The name is taken.
             */
            post {
                call.respond(HttpStatusCode.Created, materialService.create(call.tenant, call.receive<NewMaterial>()))
            }

            /**
             * Replace a material including its variants. Variants with an `id` are changed, those without are created,
             * and those in `removedVariantIds` are deleted. Every existing variant must be listed or removed. Its
             * `trackingType` can't be changed.
             *
             * Path: id [kotlin.uuid.Uuid] The material id.
             * Response: 400 application/json [api.ErrorResponse] Invalid material or variants, a variant of another material, or unknown location.
             * Response: 404 application/json [api.ErrorResponse] No material with this id.
             * Response: 409 application/json [api.ErrorResponse] The tracking type differs, the name is taken, an existing variant is neither listed nor removed, a removed variant is still in use, or a stock differs from `expectedAvailableCount` or would drop below the pieces lent out.
             */
            put("/{id}") {
                val id = call.uuidPathParameter()
                call.respond(materialService.update(call.tenant, id, call.receive<NewMaterial>()))
            }

            /**
             * Delete a material with its variants. Only possible while nothing references them (items, purchases, kits,
             * loans).
             *
             * Path: id [kotlin.uuid.Uuid] The material id.
             * Response: 404 application/json [api.ErrorResponse] No material with this id.
             * Response: 409 application/json [api.ErrorResponse] The material is still in use.
             */
            delete("/{id}") {
                materialService.delete(call.tenant, call.uuidPathParameter())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

fun Route.itemRoutes(itemService: ItemService) {
    route("/items") {
        requires(Permission.READ) {
            /**
             * Get all individual items.
             *
             * Query: materialId [kotlin.uuid.Uuid] Only return items of this material (of all its variants).
             */
            get {
                call.respond(itemService.getAll(call.tenant, call.uuidQueryParameter("materialId")))
            }

            /**
             * Get an individual item.
             *
             * Path: id [kotlin.uuid.Uuid] The item id.
             * Response: 404 application/json [api.ErrorResponse] No item with this id.
             */
            get("/{id}") {
                call.respond(itemService.get(call.tenant, call.uuidPathParameter()))
            }
        }

        requires(Permission.EDIT) {
            /**
             * Create an individual item of a variant.
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid item, or the variant isn't one of an individual material.
             * Response: 409 application/json [api.ErrorResponse] The identifier is taken.
             */
            post {
                call.respond(HttpStatusCode.Created, itemService.create(call.tenant, call.receive<NewIndividualItem>()))
            }

            /**
             * Create several individual items at once, e.g. a set of new tents. At most 100; either all of them are
             * created or none.
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid or no items, or a variant isn't one of an individual material.
             * Response: 409 application/json [api.ErrorResponse] An identifier is taken or used twice.
             */
            post("/batch") {
                val items = call.receive<List<NewIndividualItem>>()
                call.respond(HttpStatusCode.Created, itemService.createAll(call.tenant, items))
            }

            /**
             * Replace an individual item. It can move to another variant of its material (`variantId`), not to another
             * material.
             *
             * Path: id [kotlin.uuid.Uuid] The item id.
             * Response: 400 application/json [api.ErrorResponse] Invalid item, or a variant of another material.
             * Response: 404 application/json [api.ErrorResponse] No item with this id.
             * Response: 409 application/json [api.ErrorResponse] The identifier is taken.
             */
            put("/{id}") {
                val id = call.uuidPathParameter()
                call.respond(itemService.update(call.tenant, id, call.receive<NewIndividualItem>()))
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
                itemService.delete(call.tenant, call.uuidPathParameter())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
