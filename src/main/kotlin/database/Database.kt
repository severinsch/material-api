package database


import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        // TODO don't connect if envs not set
        val db_url = System.getenv("DATABASE_URL")
        if(db_url != null) {
            val postgresqldb = Database.connect(
                db_url,
                driver = "org.postgresql.Driver",
                user = System.getenv("DATABASE_USER") ?: "scout",
                password = System.getenv("DATABASE_PASSWORD") ?: "scoutpass"
            )

            transaction {
                SchemaUtils.create(StorageLocations, Materials, InventoryItems, DamageReports, Kits, KitMaterials)
            }
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
