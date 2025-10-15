package configure

import io.ktor.server.application.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*

fun Application.configureHTTP() {
    //install(HSTS) {
    //    includeSubDomains = true
    //}
    //install(HttpsRedirect) {
    //        // The port to redirect to. By default 443, the default HTTPS port.
    //        sslPort = 443
    //        // 301 Moved Permanently, or 302 Found redirect.
    //        permanentRedirect = true
    //    }

    routing {
        swaggerUI(path = "swagger", swaggerFile = "resources/openapi/open-api.json")
    }
    routing {
        openAPI(path = "openapi", swaggerFile = "resources/openapi/open-api.json")
    }
}
