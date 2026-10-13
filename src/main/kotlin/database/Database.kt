package database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class DatabaseConfig(val url: String, val user: String, val password: String)

/**
 * Entry point for all database access. The schema itself is created from db/schema.sql, not by the application.
 *
 * Creating an instance fails immediately if the database is unreachable.
 */
class Db(config: DatabaseConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        maximumPoolSize = 5
    })
    private val database = Database.connect(dataSource)

    /** Runs [block] in a transaction. JDBC is blocking, so this happens on the IO dispatcher. */
    suspend fun <T> query(block: () -> T): T = withContext(Dispatchers.IO) {
        transaction(database) { block() }
    }

    override fun close() = dataSource.close()
}
