package configure

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*


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

