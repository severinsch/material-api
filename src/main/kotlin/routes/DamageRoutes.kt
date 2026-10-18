package routes

import api.NewDamageReport
import auth.Permission
import configure.requires
import configure.tenant
import configure.tenantAccess
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.DamageService

fun Route.damageRoutes(damageService: DamageService) {
    route("/damages") {
        requires(Permission.READ) {
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
        }

        requires(Permission.REPORT_DAMAGE) {
            /**
             * Report a damage. Without the permission EDIT (viewers), only unresolved reports without resolution
             * notes, and `itemStatus` can only send an active item to repair.
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid report or unknown item.
             */
            post {
                val report = call.receive<NewDamageReport>()
                val canEdit = call.tenantAccess.permits(Permission.EDIT)
                call.respond(HttpStatusCode.Created, damageService.create(call.tenant, report, canEdit))
            }
        }

        requires(Permission.EDIT) {
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
}
