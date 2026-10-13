package database


import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object DatabaseFactory {
    private lateinit var database: Database

    val db: Database
        get() {
            check(::database.isInitialized) {
                "Database is not initialized. Set DATABASE_URL before using database-backed API routes."
            }
            return database
        }

    fun init() {
        if (::database.isInitialized) return

        val databaseUrl = System.getenv("DATABASE_URL") ?: return

        // The schema itself is created from db/schema.sql, not by the application.
        database = Database.connect(
            databaseUrl,
            driver = "org.postgresql.Driver",
            user = System.getenv("DATABASE_USER") ?: "scout",
            password = System.getenv("DATABASE_PASSWORD") ?: "scoutpass"
        )
    }
}

fun <T> dbTransaction(block: () -> T): T = transaction(DatabaseFactory.db) {
    block()
}

// TODO: enable logging based on env var and use this
fun <T> loggedTransaction(block: () -> T): T =
    transaction(DatabaseFactory.db) {
        addLogger(StdOutSqlLogger)
        block()
    }
