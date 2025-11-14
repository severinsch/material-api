package routes

import getUUIDfromPathParameter
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.kits.KitService

fun Route.kitRoutes(kitService: KitService) {
    route("/kits") {
        /**
         * Get all Kits
         *
         * @response 200 [Kit]+ A list of kits
         */
        get {
            call.respond(kitService.getAll())
        }

        /**
         * Get Kit by id
         *
         * @response 200 [Kit] The Kit
         * @path id The id of the kit to get
         *   format: uuid
         */
        get("/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@get call.respond(HttpStatusCode.BadRequest)
            val kit = kitService.getById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, kit)
        }

        /**
         * Add new Kit
         *
         * @response 201 [Kit] the newly created kit
         */
        post {
            val newKit = call.receive<NewKit>()
            val createdKit = kitService.create(newKit)
            call.respond(HttpStatusCode.OK, createdKit)
        }

        /**
         * Update Kit
         *
         * @response 200 [Kit] the updated kit
         * @path id The id of the kit to update
         *   format: uuid
         */
        put("/{id}") {
            val updatedKit = call.receive<NewKit>()
            val id = getUUIDfromPathParameter(call) ?: return@put call.respond(HttpStatusCode.BadRequest)
            val kit = kitService.update(id, updatedKit) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, kit)
        }
    }
}
