package services.material

import database.IndividualItems
import database.StorageLocations
import database.dbTransaction
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import parseUUID
import routes.IndividualItem
import routes.NewIndividualItem
import routes.StorageLocation
import java.time.LocalDate
import java.util.*


class ItemService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<IndividualItem> = dbTransaction {
        return@dbTransaction IndividualItems
            .leftJoin(StorageLocations, { IndividualItems.storageLocationId }, { StorageLocations.id })
            .selectAll()
            .where(predicate)
            .map {
                toIndividualItemResponseWithLocation(it)
            }
    }

    fun getAllOfType(typeId: UUID): List<IndividualItem> {
        return getWithCondition { IndividualItems.materialId eq typeId }
    }

    fun getById(id: UUID): IndividualItem? {
        return getWithCondition { IndividualItems.id eq id }.singleOrNull()
    }


    fun create(newIndividualItem: NewIndividualItem): IndividualItem? = dbTransaction {
        val id = IndividualItems
            .insertAndGetId {
                it[materialId] = parseUUID(newIndividualItem.materialId)
                it[identifier] = newIndividualItem.identifier
                it[condition] = newIndividualItem.condition
                it[status] = newIndividualItem.status
                it[storageLocationId] = newIndividualItem.storageLocation?.id?.let { id -> parseUUID(id) }
                it[locationDetail] = newIndividualItem.locationDetail
                it[purchasePrice] = newIndividualItem.purchasePrice?.toBigDecimal()
                it[purchaseDate] = newIndividualItem.purchaseDate?.let { d -> LocalDate.parse(d) }
            }
        return@dbTransaction getById(id.value)
    }

    fun update(id: UUID, newIndividualItem: NewIndividualItem): IndividualItem? = dbTransaction {
        IndividualItems.update({ IndividualItems.id eq id }) {
            it[materialId] = parseUUID(newIndividualItem.materialId)
            it[identifier] = newIndividualItem.identifier
            it[condition] = newIndividualItem.condition
            it[status] = newIndividualItem.status
            it[storageLocationId] = newIndividualItem.storageLocation?.id?.let { id -> parseUUID(id) }
            it[locationDetail] = newIndividualItem.locationDetail
            it[purchasePrice] = newIndividualItem.purchasePrice?.toBigDecimal()
            it[purchaseDate] = newIndividualItem.purchaseDate?.let { d -> LocalDate.parse(d) }
        }

        return@dbTransaction getById(id)
    }

    fun delete(id: UUID): Boolean = dbTransaction {
        val noDeleted = IndividualItems.deleteWhere { IndividualItems.id eq id }
        return@dbTransaction noDeleted > 0
    }

    fun toIndividualItemResponseWithLocation(row: ResultRow): IndividualItem {
        return IndividualItem(
            id = row[IndividualItems.id].toString(),
            materialId = row[IndividualItems.materialId].toString(),
            identifier = row[IndividualItems.identifier],
            condition = row[IndividualItems.condition],
            status = row[IndividualItems.status],
            storageLocation = row.getOrNull(StorageLocations.id)?.let { locationId ->
                StorageLocation(
                    id = locationId.value.toString(),
                    name = row[StorageLocations.name],
                    description = row[StorageLocations.description],
                )
            },
            locationDetail = row[IndividualItems.locationDetail],
            purchasePrice = row[IndividualItems.purchasePrice]?.toDouble(),
            purchaseDate = row[IndividualItems.purchaseDate]?.toString(),
        )
    }
}
