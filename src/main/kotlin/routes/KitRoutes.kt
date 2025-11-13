package routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.kits.KitService

fun Route.kitRoutes(kitService: KitService) {
    route("/kits") {
        /**
         * Get all Kits
         *
         * @response 200 [Kit]+ A list of kits
         */
        get {
            call.respond(kitService.getAll())
        }
    }
}
