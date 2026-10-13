package services.material

import database.StorageLocations
import database.dbTransaction
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import routes.NewStorageLocation
import routes.StorageLocation
import java.util.*

class LocationService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<StorageLocation> = dbTransaction {
        return@dbTransaction StorageLocations
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

    fun create(newStorageLocation: NewStorageLocation): StorageLocation? = dbTransaction {
        val id = StorageLocations
            .insertAndGetId {
                it[name] = newStorageLocation.name
                it[description] = newStorageLocation.description
            }
        return@dbTransaction getById(id.value)
    }

    fun delete(id: UUID): Boolean = dbTransaction {
        val noDeleted = StorageLocations.deleteWhere { StorageLocations.id eq id }
        return@dbTransaction noDeleted > 0
    }
}
