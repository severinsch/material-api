import auth.Argon2Config
import auth.LoginThrottle
import auth.Passwords
import configure.configureErrorHandling
import configure.configureRouting
import configure.configureSecurity
import configure.configureSerialization
import database.DatabaseConfig
import database.Db
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.netty.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import services.AuthService
import services.BootstrapService
import kotlin.time.Duration.Companion.hours

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val config = environment.config
    val db = Db(
        DatabaseConfig(
            url = config.property("database.url").getString(),
            user = config.property("database.user").getString(),
            password = config.property("database.password").getString()
        )
    )
    monitor.subscribe(ApplicationStopped) { db.close() }

    bootstrap(db, config)
    val authService = AuthService(db, Passwords(argon2Config(config)), LoginThrottle())
    launch {
        while (true) {
            delay(1.hours)
            // A failure (e.g. the database restarting) must not end the loop.
            runCatching { authService.cleanUp() }.onFailure { log.error("Cleaning up sessions failed", it) }
        }
    }

    configureSerialization()
    configureErrorHandling()
    configureSecurity(authService)
    configureRouting(db, authService)
}

/** Logs setup links that keep the platform administration reachable, see [BootstrapService]. */
private fun Application.bootstrap(db: Db, config: ApplicationConfig) {
    val createAdmin = config.property("auth.bootstrap").getString().toBoolean()
    val recoveryUsername = config.propertyOrNull("auth.adminSetupLinkFor")?.getString()?.trim()?.lowercase()
        ?.ifEmpty { null }
    if (!createAdmin && recoveryUsername == null) return
    val frontendUrl = config.property("auth.frontendUrl").getString().trimEnd('/')
    runBlocking { BootstrapService(db).run(createAdmin, recoveryUsername) }.forEach { (username, link) ->
        log.warn("Setup link for $username, valid until ${link.expiresAt}: $frontendUrl/setup#${link.token}")
    }
}

/** OWASP's parameters unless configured otherwise (tests use cheap ones). */
private fun argon2Config(config: ApplicationConfig): Argon2Config {
    fun int(name: String) = config.propertyOrNull("auth.argon2.$name")?.getString()?.toInt()
    val default = Argon2Config()
    return Argon2Config(
        memoryKib = int("memoryKib") ?: default.memoryKib,
        iterations = int("iterations") ?: default.iterations,
        parallelism = int("parallelism") ?: default.parallelism
    )
}
