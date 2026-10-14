package routes

import api.NewPurchase
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.PurchaseService

fun Route.purchaseRoutes(purchaseService: PurchaseService) {
    route("/purchases") {
        /**
         * Get the purchase history of bulk materials.
         *
         * Query: materialId [kotlin.uuid.Uuid] Only return purchases of this material.
         */
        get {
            call.respond(purchaseService.getAll(call.uuidQueryParameter("materialId")))
        }

        /**
         * Get a purchase.
         *
         * Path: id [kotlin.uuid.Uuid] The purchase id.
         * Response: 404 application/json [api.ErrorResponse] No purchase with this id.
         */
        get("/{id}") {
            call.respond(purchaseService.get(call.uuidPathParameter()))
        }

        /**
         * Record a purchase. Its quantity is added to the material's available stock.
         *
         * Response: 400 application/json [api.ErrorResponse] Invalid purchase, or the material isn't a bulk material.
         */
        post {
            call.respond(HttpStatusCode.Created, purchaseService.create(call.receive<NewPurchase>()))
        }

        /**
         * Replace a purchase. Its `materialId` can't be changed. The stock changes by the difference of the quantities.
         *
         * Path: id [kotlin.uuid.Uuid] The purchase id.
         * Response: 404 application/json [api.ErrorResponse] No purchase with this id.
         * Response: 409 application/json [api.ErrorResponse] The stock would drop below the pieces lent out.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(purchaseService.update(id, call.receive<NewPurchase>()))
        }

        /**
         * Delete a purchase. Its quantity is subtracted from the stock again.
         *
         * Path: id [kotlin.uuid.Uuid] The purchase id.
         * Response: 404 application/json [api.ErrorResponse] No purchase with this id.
         * Response: 409 application/json [api.ErrorResponse] The stock would drop below the pieces lent out.
         */
        delete("/{id}") {
            purchaseService.delete(call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
