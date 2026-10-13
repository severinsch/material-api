package configure

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            // Always send every field, otherwise values equal to their default (e.g. unitValue = 1) are omitted.
            encodeDefaults = true
            classDiscriminatorMode = ClassDiscriminatorMode.NONE
        })
    }
}
