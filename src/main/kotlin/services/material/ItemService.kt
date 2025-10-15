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
import routes.IndividualItemResponse
import routes.NewIndividualItem
import routes.StorageLocation
import java.time.LocalDate
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class ItemService {

    @OptIn(ExperimentalUuidApi::class)
    private fun getWithCondition(predicate: () -> Op<Boolean>): List<IndividualItemResponse> {
        return IndividualItems
            .leftJoin(StorageLocations, { IndividualItems.storageLocationId }, { StorageLocations.id })
            .selectAll()
            .where(predicate)
            .map {
                toIndividualItemResponseWithLocation(it)
            }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun getAllOfType(typeId: Uuid): List<IndividualItemResponse> {
        val id = typeId.toJavaUuid()

        return getWithCondition { IndividualItems.materialId eq id }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun getById(id: Uuid): IndividualItemResponse? {
        return getById(id.toJavaUuid())
    }

    private fun getById(id: UUID): IndividualItemResponse? {
        return getWithCondition { IndividualItems.id eq id }.singleOrNull()
    }


    @OptIn(ExperimentalUuidApi::class)
    fun create(newIndividualItem: NewIndividualItem): IndividualItemResponse? {
        val id = IndividualItems
            .insertAndGetId {
                it[materialId] = newIndividualItem.materialId.toJavaUuid()
                it[identifier] = newIndividualItem.identifier
                it[condition] = newIndividualItem.condition
                it[storageLocationId] = newIndividualItem.storageLocation?.id?.toJavaUuid()
                it[purchasePrice] = newIndividualItem.purchasePrice?.toBigDecimal()
                it[purchaseDate] = newIndividualItem.purchaseDate?.let { d -> LocalDate.parse(d) }
            }
        return getById(id.value)
    }

    @OptIn(ExperimentalUuidApi::class)
    fun delete(id: Uuid) {
        IndividualItems.deleteWhere { IndividualItems.id eq id.toJavaUuid() }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun toIndividualItemResponseWithLocation(row: ResultRow): IndividualItemResponse {
        return IndividualItemResponse(
            id = Uuid.parse(row[IndividualItems.id].toString()),
            materialId = Uuid.parse(row[IndividualItems.materialId].toString()),
            identifier = row[IndividualItems.identifier],
            condition = row[IndividualItems.condition],
            storageLocation = StorageLocation(
                id = Uuid.parse(row[StorageLocations.id].toString()),
                name = row[StorageLocations.name],
                description = row[StorageLocations.description],
            ),
            purchasePrice = row[IndividualItems.purchasePrice]?.toDouble(),
            purchaseDate = row[IndividualItems.purchaseDate]?.toString(),
        )
    }
}