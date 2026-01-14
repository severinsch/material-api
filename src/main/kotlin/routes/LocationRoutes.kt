package routes

import getUUIDfromPathParameter
import getUUIDfromQueryParameter
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.material.LocationService

fun Route.locationRoutes(locationService: LocationService) {
    route("/locations") {
        /**
         * Get all Locations
         *
         * @response 200 [StorageLocation]+ A list of damages
         */
        get {
            call.respond(locationService.getAll())
        }

        /**
         * Add new storage location
         *
         * @response 201 [StorageLocation] the newly created storage location
         */
        post {
            val newStorageLocation = call.receive<NewStorageLocation>()
            val createdStorageLocation = locationService.create(newStorageLocation) ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.Created, createdStorageLocation)
        }

        /**
         * Delete storage location
         *
         * @response 200
         * @path id The id of the location to delete
         *   format: uuid
         */
        delete("/{id}") {
            val id = getUUIDfromPathParameter(call) ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val success = locationService.delete(id)
            if (success) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
