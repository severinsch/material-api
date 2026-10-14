package services

import api.NewStorageLocation
import api.StorageLocation
import database.Db
import database.StorageLocations
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class LocationService(private val db: Db) {

    suspend fun getAll(): List<StorageLocation> = db.query {
        StorageLocations.selectAll().orderBy(StorageLocations.name).map(::toStorageLocation)
    }

    suspend fun get(id: Uuid): StorageLocation = db.query { find(id) }

    suspend fun create(location: NewStorageLocation): StorageLocation = db.query {
        val id = StorageLocations.insertAndGetId { it.fill(location) }
        find(id.value)
    }

    suspend fun update(id: Uuid, location: NewStorageLocation): StorageLocation = db.query {
        if (StorageLocations.update({ StorageLocations.id eq id }) { it.fill(location) } == 0) notFound("Lagerort", id)
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (StorageLocations.deleteWhere { StorageLocations.id eq id } == 0) notFound("Lagerort", id)
    }

    private fun find(id: Uuid): StorageLocation =
        StorageLocations.selectAll().where { StorageLocations.id eq id }.singleOrNull()?.let(::toStorageLocation)
            ?: notFound("Lagerort", id)

    private fun UpdateBuilder<*>.fill(location: NewStorageLocation) {
        this[StorageLocations.name] = location.name
        this[StorageLocations.description] = location.description
    }

    companion object {
        /** Maps a row that contains the [StorageLocations] columns, or null for a missing (left joined) location. */
        fun toStorageLocationOrNull(row: ResultRow): StorageLocation? =
            row.getOrNull(StorageLocations.id)?.let { toStorageLocation(row) }

        private fun toStorageLocation(row: ResultRow) = StorageLocation(
            id = row[StorageLocations.id].value,
            name = row[StorageLocations.name],
            description = row[StorageLocations.description]
        )
    }
}
