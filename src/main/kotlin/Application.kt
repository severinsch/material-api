import configure.configureHTTP
import configure.configureRouting
import configure.configureSecurity
import configure.configureSerialization
import database.DatabaseFactory
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    DatabaseFactory.init()
    configureHTTP()
    configureSecurity()
    configureRouting()
    configureSerialization()
}
