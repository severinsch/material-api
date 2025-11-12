package services.material

import database.IndividualItems
import database.StorageLocations
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import parseUUID
import routes.IndividualItemResponse
import routes.NewIndividualItem
import routes.StorageLocation
import java.time.LocalDate
import java.util.*


class ItemService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<IndividualItemResponse> = transaction {
        return@transaction IndividualItems
            .leftJoin(StorageLocations, { IndividualItems.storageLocationId }, { StorageLocations.id })
            .selectAll()
            .where(predicate)
            .map {
                toIndividualItemResponseWithLocation(it)
            }
    }

    fun getAllOfType(typeId: UUID): List<IndividualItemResponse> {
        return getWithCondition { IndividualItems.materialId eq typeId }
    }

    fun getById(id: UUID): IndividualItemResponse? {
        return getWithCondition { IndividualItems.id eq id }.singleOrNull()
    }


    fun create(newIndividualItem: NewIndividualItem): IndividualItemResponse? = transaction {
        val id = IndividualItems
            .insertAndGetId {
                it[materialId] = parseUUID(newIndividualItem.materialId)
                it[identifier] = newIndividualItem.identifier
                it[condition] = newIndividualItem.condition
                it[storageLocationId] = newIndividualItem.storageLocation?.id?.let { id -> parseUUID(id) }
                it[purchasePrice] = newIndividualItem.purchasePrice?.toBigDecimal()
                it[purchaseDate] = newIndividualItem.purchaseDate?.let { d -> LocalDate.parse(d) }
            }
        return@transaction getById(id.value)
    }

    fun update(id: UUID, newIndividualItem: NewIndividualItem): IndividualItemResponse? = transaction {
        IndividualItems.update({ IndividualItems.id eq id }) {
            it[materialId] = parseUUID(newIndividualItem.materialId)
            it[identifier] = newIndividualItem.identifier
            it[condition] = newIndividualItem.condition
            it[storageLocationId] = newIndividualItem.storageLocation?.id?.let { id -> parseUUID(id) }
            it[purchasePrice] = newIndividualItem.purchasePrice?.toBigDecimal()
            it[purchaseDate] = newIndividualItem.purchaseDate?.let { d -> LocalDate.parse(d) }
        }

        return@transaction getById(id)
    }

    fun delete(id: UUID): Boolean = transaction {
        val noDeleted = IndividualItems.deleteWhere { IndividualItems.id eq id }
        return@transaction noDeleted > 0
    }

    fun toIndividualItemResponseWithLocation(row: ResultRow): IndividualItemResponse {
        return IndividualItemResponse(
            id = row[IndividualItems.id].toString(),
            materialId = row[IndividualItems.materialId].toString(),
            identifier = row[IndividualItems.identifier],
            condition = row[IndividualItems.condition],
            storageLocation = row.getOrNull(StorageLocations.id)?.let { locationId ->
                StorageLocation(
                    id = locationId.value.toString(),
                    name = row[StorageLocations.name],
                    description = row[StorageLocations.description],
                )
            },
            purchasePrice = row[IndividualItems.purchasePrice]?.toDouble(),
            purchaseDate = row[IndividualItems.purchaseDate]?.toString(),
        )
    }
}