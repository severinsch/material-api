package database


import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
                SchemaUtils.create(StorageLocations, Materials, IndividualItems, DamageReports, Kits, KitMaterials)
            }
        }
    }
}
