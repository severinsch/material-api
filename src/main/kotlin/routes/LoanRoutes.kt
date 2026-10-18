package routes

import configure.tenant
import api.NewLoan
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.LoanService

fun Route.loanRoutes(loanService: LoanService) {
    route("/loans") {
        /**
         * Get all loans.
         *
         * Query: open [kotlin.Boolean] If set, only open (true) or only returned (false) loans.
         */
        get {
            call.respond(loanService.getAll(call.tenant, call.booleanQueryParameter("open")))
        }

        /**
         * Get a loan.
         *
         * Path: id [kotlin.uuid.Uuid] The loan id.
         * Response: 404 application/json [api.ErrorResponse] No loan with this id.
         */
        get("/{id}") {
            call.respond(loanService.get(call.tenant, call.uuidPathParameter()))
        }

        /**
         * Record a loan: quantities of materials (`materials`), specific items (`itemIds`), or both.
         *
         * Response: 400 application/json [api.ErrorResponse] Invalid loan or unknown item or material.
         * Response: 409 application/json [api.ErrorResponse] Something isn't available (inactive, lent out, out of stock).
         */
        post {
            call.respond(HttpStatusCode.Created, loanService.create(call.tenant, call.receive<NewLoan>()))
        }

        /**
         * Replace a loan including its items. Set `returnedAt` to mark it as returned.
         *
         * Path: id [kotlin.uuid.Uuid] The loan id.
         * Response: 404 application/json [api.ErrorResponse] No loan with this id.
         * Response: 409 application/json [api.ErrorResponse] Same as for creating a loan.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(loanService.update(call.tenant, id, call.receive<NewLoan>()))
        }

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
