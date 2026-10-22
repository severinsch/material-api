package routes

import api.NewLoan
import auth.Permission
import configure.requires
import configure.tenant
import configure.tenantAccess
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.LoanService

fun Route.loanRoutes(loanService: LoanService) {
    route("/loans") {
        requires(Permission.BORROW) {
            /**
             * Get all loans; without the permission EDIT only the user's own.
             *
             * Query: open [kotlin.Boolean] If set, only open (true) or only returned (false) loans.
             */
            get {
                call.respond(loanService.getAll(call.tenantAccess, call.booleanQueryParameter("open")))
            }

            /**
             * Get a loan.
             *
             * Path: id [kotlin.uuid.Uuid] The loan id.
             * Response: 404 application/json [api.ErrorResponse] No loan with this id that the user may see.
             */
            get("/{id}") {
                call.respond(loanService.get(call.tenantAccess, call.uuidPathParameter()))
            }

            /**
             * Record a loan: quantities of material variants (`materials`), specific items (`itemIds`), or both. Without
             * the permission EDIT only for the user themselves (`borrowerId`).
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid loan, unknown item or variant, or the borrower isn't a member.
             * Response: 409 application/json [api.ErrorResponse] Something isn't available (inactive, lent out, out of stock).
             */
            post {
                call.respond(HttpStatusCode.Created, loanService.create(call.tenantAccess, call.receive<NewLoan>()))
            }

            /**
             * Replace a loan including its items. Set `returnedAt` to mark it as returned. Without the permission EDIT
             * only the user's own open loans, and they stay theirs.
             *
             * Path: id [kotlin.uuid.Uuid] The loan id.
             * Response: 404 application/json [api.ErrorResponse] No loan with this id that the user may see.
             * Response: 409 application/json [api.ErrorResponse] Same as for creating a loan.
             */
            put("/{id}") {
                val id = call.uuidPathParameter()
                call.respond(loanService.update(call.tenantAccess, id, call.receive<NewLoan>()))
            }
        }

        requires(Permission.EDIT) {
            /**
             * Delete a loan.
             *
             * Path: id [kotlin.uuid.Uuid] The loan id.
             * Response: 404 application/json [api.ErrorResponse] No loan with this id.
             */
            delete("/{id}") {
                loanService.delete(call.tenant, call.uuidPathParameter())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
