package routes

import api.NewDamageReport
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.DamageService

fun Route.damageRoutes(damageService: DamageService) {
    route("/damages") {
        /**
         * Get all damage reports.
         *
         * Query: item_id [kotlin.uuid.Uuid] Only return reports for this individual item.
         */
        get {
            call.respond(damageService.getAll(call.uuidQueryParameter("item_id")))
        }

        /**
         * Get a damage report.
         *
         * Path: id [kotlin.uuid.Uuid] The report id.
         * Response: 404 application/json [api.ErrorResponse] No report with this id.
         */
        get("/{id}") {
            call.respond(damageService.get(call.uuidPathParameter()))
        }

        /**
         * Report a damage.
         *
         * Response: 409 application/json [api.ErrorResponse] The item doesn't exist.
         */
        post {
            call.respond(HttpStatusCode.Created, damageService.create(call.receive<NewDamageReport>()))
        }

        /**
         * Replace a damage report, e.g. to resolve it.
         *
         * Path: id [kotlin.uuid.Uuid] The report id.
         * Response: 404 application/json [api.ErrorResponse] No report with this id.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(damageService.update(id, call.receive<NewDamageReport>()))
        }

        /**
         * Delete a damage report.
         *
         * Path: id [kotlin.uuid.Uuid] The report id.
         * Response: 404 application/json [api.ErrorResponse] No report with this id.
         */
        delete("/{id}") {
            damageService.delete(call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
