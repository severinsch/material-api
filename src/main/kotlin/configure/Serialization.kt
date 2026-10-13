package configure

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

val ApiJson = Json {
    prettyPrint = true
    // Always send every field, otherwise values equal to their default (e.g. unitValue = 1) are omitted.
    encodeDefaults = true
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(ApiJson)
    }
}
