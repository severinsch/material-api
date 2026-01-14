package configure

import io.ktor.server.application.*
import io.ktor.server.routing.*
import routes.damageRoutes
import routes.kitRoutes
import routes.locationRoutes
import routes.materialRoutes
import services.DamageService
import services.kits.KitService
import services.material.ItemService
import services.material.LocationService
import services.material.MaterialService

fun Application.configureRouting() {
    val materialService = MaterialService()
    val itemService = ItemService()
    val damageService = DamageService()
    val kitService = KitService()
    val locationService = LocationService()
    routing {
        materialRoutes(materialService, itemService)
        damageRoutes(damageService)
        kitRoutes(kitService)
        locationRoutes(locationService)
    }
}
