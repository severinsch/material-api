package database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

data class DatabaseConfig(val url: String, val user: String, val password: String)

/** A tenant whose data a transaction may access, see [Db.tenantQuery]. */
@JvmInline
value class TenantId(val value: Uuid)

/**
 * Entry point for all database access. Creating an instance brings the schema up to date with the migrations in
 * src/main/resources/db/migration, and fails immediately if the database is unreachable or a migration fails.
 *
 * Transactions run as the role [APP_ROLE], which is subject to the row-level security policies of the tenant-owned
 * tables. The configured user owns the tables and is only used by Flyway.
 */
class Db(config: DatabaseConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        maximumPoolSize = 5
    }).also { Flyway.configure().dataSource(it).load().migrate() }
    private val database = Database.connect(dataSource)

    init {
        // Policies don't apply to superusers and roles with BYPASSRLS, e.g. if the role was created by hand.
        val bypassesPolicies = transaction(database) {
            setAppRole()
            exec("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user") { it.next(); it.getBoolean(1) }
        }
        check(bypassesPolicies == false) { "The database role $APP_ROLE must be neither superuser nor BYPASSRLS." }
    }

    /**
     * Runs [block] in a transaction without a tenant, for accounts and platform administration. Tenant-owned tables
     * appear empty there, and inserting into them fails.
     */
    suspend fun <T> query(block: () -> T): T = appTransaction { block() }

    /**
     * Runs [block] in a transaction that only sees and writes data of [tenant]: row-level security filters all
     * queries, and inserts get the tenant through the column default.
     */
    suspend fun <T> tenantQuery(tenant: TenantId, block: () -> T): T = appTransaction {
        exec("SELECT set_config('app.tenant_id', ?, true)", listOf(TextColumnType() to tenant.value.toString()))
        block()
    }

    /** JDBC is blocking, so this happens on the IO dispatcher. Role and tenant are transaction-local settings. */
    private suspend fun <T> appTransaction(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(database) {
            setAppRole()
            block()
        }
    }

    private fun JdbcTransaction.setAppRole() = exec("SET LOCAL ROLE $APP_ROLE")

    override fun close() = dataSource.close()

    companion object {
        /** Created by the migrations. */
        const val APP_ROLE = "material_app"
    }
}
