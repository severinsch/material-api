package routes

import configure.tenant
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
         * Query: itemId [kotlin.uuid.Uuid] Only return reports for this individual item.
         */
        get {
            call.respond(damageService.getAll(call.tenant, call.uuidQueryParameter("itemId")))
        }

        /**
         * Get a damage report.
         *
         * Path: id [kotlin.uuid.Uuid] The report id.
         * Response: 404 application/json [api.ErrorResponse] No report with this id.
         */
        get("/{id}") {
            call.respond(damageService.get(call.tenant, call.uuidPathParameter()))
        }

        /**
         * Report a damage.
         *
         * Response: 400 application/json [api.ErrorResponse] Invalid report or unknown item.
         */
        post {
            call.respond(HttpStatusCode.Created, damageService.create(call.tenant, call.receive<NewDamageReport>()))
        }

        /**
         * Replace a damage report, e.g. to resolve it. Its `itemId` can't be changed.
         *
         * Path: id [kotlin.uuid.Uuid] The report id.
         * Response: 404 application/json [api.ErrorResponse] No report with this id.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(damageService.update(call.tenant, id, call.receive<NewDamageReport>()))
        }

        /**
         * Delete a damage report.
         *
         * Path: id [kotlin.uuid.Uuid] The report id.
         * Response: 404 application/json [api.ErrorResponse] No report with this id.
         */
        delete("/{id}") {
            damageService.delete(call.tenant, call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
