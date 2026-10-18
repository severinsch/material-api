package services

import database.Db
import database.TenantId
import database.Tenants
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

class TenantService(private val db: Db) {

    /** The tenant with this id, unless it doesn't exist or is deleted. */
    suspend fun findActive(id: Uuid): TenantId? = db.query {
        Tenants.select(Tenants.id)
            .where { (Tenants.id eq id) and Tenants.deletedAt.isNull() }
            .singleOrNull()
            ?.let { TenantId(it[Tenants.id].value) }
    }
}
