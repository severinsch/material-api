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
import parseUUID
import routes.IndividualItemResponse
import routes.NewIndividualItem
import routes.StorageLocation
import java.time.LocalDate
import java.util.*


class ItemService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<IndividualItemResponse> {
        return IndividualItems
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


    fun create(newIndividualItem: NewIndividualItem): IndividualItemResponse? {
        val id = IndividualItems
            .insertAndGetId {
                it[materialId] = parseUUID(newIndividualItem.materialId)
                it[identifier] = newIndividualItem.identifier
                it[condition] = newIndividualItem.condition
                it[storageLocationId] = newIndividualItem.storageLocation?.id?.let { id -> parseUUID(id) }
                it[purchasePrice] = newIndividualItem.purchasePrice?.toBigDecimal()
                it[purchaseDate] = newIndividualItem.purchaseDate?.let { d -> LocalDate.parse(d) }
            }
        return getById(id.value)
    }

    fun delete(id: UUID) {
        IndividualItems.deleteWhere { IndividualItems.id eq id }
    }

    fun toIndividualItemResponseWithLocation(row: ResultRow): IndividualItemResponse {
        return IndividualItemResponse(
            id = row[IndividualItems.id].toString(),
            materialId = row[IndividualItems.materialId].toString(),
            identifier = row[IndividualItems.identifier],
            condition = row[IndividualItems.condition],
            storageLocation = StorageLocation(
                id = row[StorageLocations.id].toString(),
                name = row[StorageLocations.name],
                description = row[StorageLocations.description],
            ),
            purchasePrice = row[IndividualItems.purchasePrice]?.toDouble(),
            purchaseDate = row[IndividualItems.purchaseDate]?.toString(),
        )
    }
}