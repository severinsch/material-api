package routes

import getUUIDfromPathParameter
import getUUIDfromQueryParameter
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.DamageService

fun Route.damageRoutes(damageService: DamageService) {
    route("/damages") {
        /**
         * Get all Damages
         *
         * @query item_id The id of the item to get damages for. Get all Damages if not provided.
         *   format: uuid
         * @response 200 [DamageReport]+ A list of damages
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

        /**
         * Register new damage
         *
         * @response 201 [DamageReport] the newly created damage report
         */
        post {
            val newDamageReport = call.receive<NewDamageReport>()
            val createdDamageReport = damageService.create(newDamageReport)
            call.respond(HttpStatusCode.Created, createdDamageReport)
        }

        /**
         * Update damage
         *
         * @response 200 [DamageReport] the updated damage report
         * @path id The id of the damage to update
         *   format: uuid
         */
        put("/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@put call.respond(HttpStatusCode.BadRequest)
            val newDamageReport = call.receive<NewDamageReport>()
            val updatedDamageReport =
                damageService.update(id, newDamageReport) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.OK, updatedDamageReport)
        }

        /**
         * Delete damage
         *
         * @response 200
         * @path id The id of the damage to delete
         *   format: uuid
         */
        delete("/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val success = damageService.delete(id)
            if (success) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
