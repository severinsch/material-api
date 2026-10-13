package services

import api.MaterialTrackingType
import api.NewPurchase
import api.Purchase
import database.Db
import database.Purchases
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

class PurchaseService(private val db: Db) {

    suspend fun getAll(materialId: Uuid? = null): List<Purchase> = db.query {
        findWhere { materialId?.let { Purchases.materialId eq it } ?: Op.TRUE }
    }

    suspend fun get(id: Uuid): Purchase = db.query { find(id) }

    suspend fun create(purchase: NewPurchase): Purchase = db.query {
        requireMaterial(purchase.materialId, MaterialTrackingType.BULK)
        val id = Purchases.insertAndGetId { it.fill(purchase) }
        find(id.value)
    }

    suspend fun update(id: Uuid, purchase: NewPurchase): Purchase = db.query {
        requireUnchanged("materialId", find(id).materialId, purchase.materialId)
        Purchases.update({ Purchases.id eq id }) { it.fill(purchase) }
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (Purchases.deleteWhere { Purchases.id eq id } == 0) notFound("Purchase", id)
    }

    private fun find(id: Uuid): Purchase = findWhere { Purchases.id eq id }.singleOrNull() ?: notFound("Purchase", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<Purchase> = Purchases
        .selectAll()
        .where(predicate)
        .orderBy(Purchases.purchaseDate to SortOrder.DESC_NULLS_LAST)
        .map {
            Purchase(
                id = it[Purchases.id].value,
                materialId = it[Purchases.materialId].value,
                quantity = it[Purchases.quantity],
                unitPrice = it[Purchases.unitPrice]?.toDouble(),
                purchaseDate = it[Purchases.purchaseDate],
                note = it[Purchases.note]
            )
        }

    private fun UpdateBuilder<*>.fill(purchase: NewPurchase) {
        this[Purchases.materialId] = purchase.materialId
        this[Purchases.quantity] = purchase.quantity
        this[Purchases.unitPrice] = purchase.unitPrice?.toBigDecimal()
        this[Purchases.purchaseDate] = purchase.purchaseDate
        this[Purchases.note] = purchase.note
    }
}
