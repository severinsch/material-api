package configure

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.plugins.cors.routing.CORS


fun Application.configureSecurity() {
    // TODO: add authentication in the future
    authentication {
    }
    install(Sessions) {
    }
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)

    }
    routing {
    }
}

