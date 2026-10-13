import configure.configureCors
import configure.configureErrorHandling
import configure.configureRouting
import configure.configureSerialization
import database.DatabaseConfig
import database.Db
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val db = Db(
        DatabaseConfig(
            url = environment.config.property("database.url").getString(),
            user = environment.config.property("database.user").getString(),
            password = environment.config.property("database.password").getString()
        )
    )
    monitor.subscribe(ApplicationStopped) { db.close() }

    configureSerialization()
    configureErrorHandling()
    configureCors()
    configureRouting(db)
}
