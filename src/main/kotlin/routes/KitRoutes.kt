package routes

import api.NewKit
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.KitService

fun Route.kitRoutes(kitService: KitService) {
    route("/kits") {
        /**
         * Get all kits.
         */
        get {
            call.respond(kitService.getAll())
        }

        /**
         * Get a kit.
         *
         * Path: id [kotlin.uuid.Uuid] The kit id.
         * Response: 404 application/json [api.ErrorResponse] No kit with this id.
         */
        get("/{id}") {
            call.respond(kitService.get(call.uuidPathParameter()))
        }

        /**
         * Create a kit.
         *
         * Response: 409 application/json [api.ErrorResponse] The name is taken or a referenced material doesn't exist.
         */
        post {
            call.respond(HttpStatusCode.Created, kitService.create(call.receive<NewKit>()))
        }

        /**
         * Replace a kit including all of its groups.
         *
         * Path: id [kotlin.uuid.Uuid] The kit id.
         * Response: 404 application/json [api.ErrorResponse] No kit with this id.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(kitService.update(id, call.receive<NewKit>()))
        }

        /**
         * Delete a kit.
         *
         * Path: id [kotlin.uuid.Uuid] The kit id.
         * Response: 404 application/json [api.ErrorResponse] No kit with this id.
         */
        delete("/{id}") {
            kitService.delete(call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
