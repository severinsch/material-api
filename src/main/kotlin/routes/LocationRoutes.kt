package routes

import configure.tenant
import api.NewStorageLocation
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.LocationService

fun Route.locationRoutes(locationService: LocationService) {
    route("/locations") {
        /**
         * Get all storage locations.
         */
        get {
            call.respond(locationService.getAll(call.tenant))
        }

        /**
         * Get a storage location.
         *
         * Path: id [kotlin.uuid.Uuid] The location id.
         * Response: 404 application/json [api.ErrorResponse] No location with this id.
         */
        get("/{id}") {
            call.respond(locationService.get(call.tenant, call.uuidPathParameter()))
        }

        /**
         * Create a storage location.
         *
         * Response: 409 application/json [api.ErrorResponse] The name is taken.
         */
        post {
            val location = call.receive<NewStorageLocation>()
            call.respond(HttpStatusCode.Created, locationService.create(call.tenant, location))
        }

        /**
         * Replace a storage location.
         *
         * Path: id [kotlin.uuid.Uuid] The location id.
         * Response: 404 application/json [api.ErrorResponse] No location with this id.
         */
        put("/{id}") {
            val id = call.uuidPathParameter()
            call.respond(locationService.update(call.tenant, id, call.receive<NewStorageLocation>()))
        }

        /**
         * Delete a storage location. Materials and items stored there keep existing without a location.
         *
         * Path: id [kotlin.uuid.Uuid] The location id.
         * Response: 404 application/json [api.ErrorResponse] No location with this id.
         */
        delete("/{id}") {
            locationService.delete(call.tenant, call.uuidPathParameter())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
