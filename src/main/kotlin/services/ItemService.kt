package services

import api.IndividualItem
import api.NewIndividualItem
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

class ItemService(private val db: Db) {

    suspend fun getByMaterial(materialId: Uuid): List<IndividualItem> = db.query {
        findWhere { IndividualItems.materialId eq materialId }
    }

    suspend fun get(id: Uuid): IndividualItem = db.query { find(id) }

    suspend fun create(item: NewIndividualItem): IndividualItem = db.query {
        val id = IndividualItems.insertAndGetId { it.fill(item) }
        find(id.value)
    }

    suspend fun update(id: Uuid, item: NewIndividualItem): IndividualItem = db.query {
        if (IndividualItems.update({ IndividualItems.id eq id }) { it.fill(item) } == 0) notFound("Item", id)
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (IndividualItems.deleteWhere { IndividualItems.id eq id } == 0) notFound("Item", id)
    }

    private fun find(id: Uuid): IndividualItem =
        findWhere { IndividualItems.id eq id }.singleOrNull() ?: notFound("Item", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<IndividualItem> {
        val onLoan = exists(
            LoanLines.innerJoin(Loans).select(LoanLines.id).where {
                (LoanLines.individualItemId eq IndividualItems.id) and Loans.returnedAt.isNull()
            }
        )
        return IndividualItems
            .leftJoin(StorageLocations)
            .select(IndividualItems.columns + StorageLocations.columns + onLoan)
            .where(predicate)
            .orderBy(IndividualItems.identifier)
            .map { row ->
                IndividualItem(
                    id = row[IndividualItems.id].value,
                    materialId = row[IndividualItems.materialId].value,
                    identifier = row[IndividualItems.identifier],
                    condition = row[IndividualItems.condition],
                    status = row[IndividualItems.status],
                    storageLocation = LocationService.toStorageLocationOrNull(row),
                    locationDetail = row[IndividualItems.locationDetail],
                    purchasePrice = row[IndividualItems.purchasePrice]?.toDouble(),
                    purchaseDate = row[IndividualItems.purchaseDate],
                    onLoan = row[onLoan]
                )
            }
    }

    private fun UpdateBuilder<*>.fill(item: NewIndividualItem) {
        this[IndividualItems.materialId] = item.materialId
        this[IndividualItems.identifier] = item.identifier
        this[IndividualItems.condition] = item.condition
        this[IndividualItems.status] = item.status
        this[IndividualItems.storageLocationId] = item.storageLocationId
        this[IndividualItems.locationDetail] = item.locationDetail
        this[IndividualItems.purchasePrice] = item.purchasePrice?.toBigDecimal()
        this[IndividualItems.purchaseDate] = item.purchaseDate
    }
}
