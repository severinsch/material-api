package routes

import api.NewKit
import auth.Permission
import configure.requires
import configure.tenant
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.KitService

fun Route.kitRoutes(kitService: KitService) {
    route("/kits") {
        requires(Permission.READ) {
            /**
             * Get all kits.
             */
            get {
                call.respond(kitService.getAll(call.tenant))
            }

            /**
             * Get a kit.
             *
             * Path: id [kotlin.uuid.Uuid] The kit id.
             * Response: 404 application/json [api.ErrorResponse] No kit with this id.
             */
            get("/{id}") {
                call.respond(kitService.get(call.tenant, call.uuidPathParameter()))
            }
        }

        requires(Permission.EDIT) {
            /**
             * Create a kit. An option refers to a material (any variant) or to one of its variants (`variantId`).
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid kit, overlapping options, unknown material or variant, or a variant of another material.
             * Response: 409 application/json [api.ErrorResponse] The name is taken.
             */
            post {
                call.respond(HttpStatusCode.Created, kitService.create(call.tenant, call.receive<NewKit>()))
            }

            /**
             * Replace a kit including all of its groups.
             *
             * Path: id [kotlin.uuid.Uuid] The kit id.
             * Response: 404 application/json [api.ErrorResponse] No kit with this id.
             */
            put("/{id}") {
                val id = call.uuidPathParameter()
                call.respond(kitService.update(call.tenant, id, call.receive<NewKit>()))
            }

            /**
             * Delete a kit.
             *
             * Path: id [kotlin.uuid.Uuid] The kit id.
             * Response: 404 application/json [api.ErrorResponse] No kit with this id.
             */
            delete("/{id}") {
                kitService.delete(call.tenant, call.uuidPathParameter())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
