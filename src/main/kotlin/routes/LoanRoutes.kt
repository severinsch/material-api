package routes

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
         * Query: open [Boolean] true: only loans that haven't been returned, false: only returned loans.
         */
        get {
            call.respond(loanService.getAll(call.booleanQueryParameter("open")))
        }

        /**
         * Get a loan.
         *
         * Path: id [kotlin.uuid.Uuid] The loan id.
         * Response: 404 application/json [api.ErrorResponse] No loan with this id.
         */
        get("/{id}") {
            call.respond(loanService.get(call.uuidPathParameter()))
        }

        /**
         * Record a loan.
         *
         * Response: 409 application/json [api.ErrorResponse] Items are already lent out or a reference doesn't exist.
         */
        post {
            call.respond(HttpStatusCode.Created, loanService.create(call.receive<NewLoan>()))
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
            call.respond(loanService.update(id, call.receive<NewLoan>()))
        }

        /**
         * Delete a loan.
         *
         * Path: id [kotlin.uuid.Uuid] The loan id.
         * Response: 404 application/json [api.ErrorResponse] No loan with this id.
         */
        delete("/{id}") {
            loanService.delete(call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
