package configure

import database.Db
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.server.application.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.OpenApiDocSource
import routes.*
import services.*

fun Application.configureRouting(db: Db) {
    val materialService = MaterialService(db)
    val itemService = ItemService(db)
    routing {
        // Registered first so the OpenAPI schema `StorageLocation` is the non-null one; the nullable variant used
        // as a property elsewhere gets a generated name.
        locationRoutes(LocationService(db))
        materialRoutes(materialService, itemService)
        purchaseRoutes(PurchaseService(db))
        damageRoutes(DamageService(db))
        kitRoutes(KitService(db))
        loanRoutes(LoanService(db))

        // The OpenAPI document is assembled at runtime from the routes above.
        val info = OpenApiInfo("Material API", "1.0", description = "API for managing scout material")
        val source = OpenApiDocSource.Routing(ContentType.Application.Json)
        swaggerUI("swagger") {
            this.info = info
            this.source = source
        }
        openAPI("openapi") {
            this.info = info
            this.source = source
        }
    }
}
