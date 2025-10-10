package configure

import io.ktor.server.application.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import routes.damageRoutes
import routes.materialRoutes
import services.DamageService
import services.MaterialService

fun Application.configureRouting() {
    val materialService = MaterialService()
    val damageService = DamageService()
    routing {
        materialRoutes(materialService)
        damageRoutes(damageService)
        route("/") {
            get {
                call.respondText("Hello World!")
            }
        }
    }
}
