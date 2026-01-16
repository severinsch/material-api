package services.material

import database.StorageLocations
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import routes.NewStorageLocation
import routes.StorageLocation
import java.util.*

class LocationService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<StorageLocation> = transaction {
        return@transaction StorageLocations
            .selectAll()
            .where(predicate)
            .map { row ->
                StorageLocation(
                    row[StorageLocations.id].toString(),
                    row[StorageLocations.name],
                    row[StorageLocations.description]
                )
            }
    }

    fun getById(id: UUID): StorageLocation? {
        return getWithCondition { StorageLocations.id eq id }.singleOrNull()
    }

    fun getAll(): List<StorageLocation> {
        return getWithCondition { Op.TRUE }
    }

    fun create(newStorageLocation: NewStorageLocation): StorageLocation? = transaction {
        val id = StorageLocations
            .insertAndGetId {
                it[name] = newStorageLocation.name
                it[description] = newStorageLocation.description
            }
        return@transaction getById(id.value)
    }

    fun delete(id: UUID): Boolean = transaction {
        val noDeleted = StorageLocations.deleteWhere { StorageLocations.id eq id }
        return@transaction noDeleted > 0
    }
}
