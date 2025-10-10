package configure

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*


fun Application.configureSecurity() {
    // TODO: add authentication in the future
    authentication {
    }
    install(Sessions) {
    }
    routing {
    }
}

