package configure

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

/** Allows browser requests from the configured frontend hosts (`cors.allowedHosts`, comma-separated). */
fun Application.configureCors() {
    val hosts = environment.config.propertyOrNull("cors.allowedHosts")?.getString()
        ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
        .orEmpty()
    if (hosts.isEmpty()) return

    install(CORS) {
        hosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
    }
}
