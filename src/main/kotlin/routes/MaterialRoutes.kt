package routes

import services.MaterialService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class Material(
    val id: String,
    val name: String,
    val trackingType: String,
    val storageLocationId: String?,
    val quantityAvailable: Int?,
    val quantityDamaged: Int?,
    val description: String?,
    val category: String?,
    val purchasePrice: Double?,
    val purchaseDate: String?
)

@Serializable
data class NewMaterial(
    val name: String,
    val trackingType: String,
    val storageLocationId: String? = null,
    val quantityAvailable: Int? = null,
    val quantityDamaged: Int? = null,
    val description: String? = null,
    val category: String? = null,
    val purchasePrice: Double? = null,
    val purchaseDate: String? = null
)

fun Route.materialRoutes(materialService: MaterialService) {
    route("/materials") {
        get {
            call.respond(materialService.getAll())
        }
        post {
            val newMaterial = call.receive<NewMaterial>()
            val createdMaterial = materialService.create(newMaterial)
            call.respond(HttpStatusCode.Created, createdMaterial)
        }
    }
}
