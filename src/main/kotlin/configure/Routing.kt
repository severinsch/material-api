package configure

import io.ktor.server.application.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import routes.damageRoutes
import routes.materialRoutes
import services.DamageService
import services.material.ItemService
import services.material.MaterialService

fun Application.configureRouting() {
    val materialService = MaterialService()
    val itemService = ItemService()
    val damageService = DamageService()
    routing {
        materialRoutes(materialService, itemService)
        damageRoutes(damageService)
        route("/") {
            get {
                call.respondText("Hello World!")
            }
        }
    }
}
