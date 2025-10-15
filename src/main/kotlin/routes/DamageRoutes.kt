package routes

import services.DamageService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class DamageReport(
    val id: String,
    val inventoryItemId: String,
    val description: String,
    val isResolved: Boolean,
    val reportedAt: String
)

@Serializable
data class NewDamageReport(
    val inventoryItemId: String,
    val description: String,
    val isResolved: Boolean = false
)

fun Route.damageRoutes(damageService: DamageService) {
    route("/damages") {
        get {
            call.respond(damageService.getAll())
        }
        post {
            val newDamageReport = call.receive<NewDamageReport>()
            val createdDamageReport = damageService.create(newDamageReport)
            call.respond(HttpStatusCode.Created, createdDamageReport)
        }
    }
}
