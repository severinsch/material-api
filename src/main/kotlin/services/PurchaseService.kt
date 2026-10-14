package services

import api.MaterialTrackingType
import api.NewPurchase
import api.Purchase
import database.BulkStock
import database.Db
import database.Purchases
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

/** Purchases add to the stock of their material: creating, changing and deleting one adjusts the stock. */
class PurchaseService(private val db: Db) {

    suspend fun getAll(materialId: Uuid? = null): List<Purchase> = db.query {
        findWhere { materialId?.let { Purchases.materialId eq it } ?: Op.TRUE }
    }

    suspend fun get(id: Uuid): Purchase = db.query { find(id) }

    suspend fun create(purchase: NewPurchase): Purchase = db.query {
        requireMaterial(purchase.materialId, MaterialTrackingType.BULK)
        val id = Purchases.insertAndGetId { it.fill(purchase) }
        adjustStock(purchase.materialId, purchase.quantity)
        find(id.value)
    }

    suspend fun update(id: Uuid, purchase: NewPurchase): Purchase = db.query {
        val existing = find(id, lock = true)
        requireUnchanged("Das Material eines Einkaufs", existing.materialId, purchase.materialId)
        Purchases.update({ Purchases.id eq id }) { it.fill(purchase) }
        adjustStock(purchase.materialId, purchase.quantity - existing.quantity)
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        val existing = find(id, lock = true)
        Purchases.deleteWhere { Purchases.id eq id }
        adjustStock(existing.materialId, -existing.quantity)
    }

    /**
     * Changes the available stock by [delta]. Rejects decreases below the pieces that are lent out, e.g. deleting a
     * purchase whose pieces were used up or are on a loan.
     */
    private fun adjustStock(materialId: Uuid, delta: Int) {
        if (delta == 0) return
        val available = lockStock(materialId)
        if (delta < 0) {
            val lent = loanedCount(materialId)
            if (available + delta < lent) {
                val lentNote = if (lent > 0) ", davon $lent ausgeliehen" else ""
                conflict(
                    "Von ${materialName(materialId)} sind $available im Bestand$lentNote. " +
                        "Es können nicht ${-delta} abgezogen werden."
                )
            }
        }
        BulkStock.update({ BulkStock.materialId eq materialId }) { it[quantityAvailable] = available + delta }
    }

    /** With [lock], concurrent changes of the purchase wait until the transaction ends, so the stock is adjusted once. */
    private fun find(id: Uuid, lock: Boolean = false): Purchase =
        findWhere(lock) { Purchases.id eq id }.singleOrNull() ?: notFound("Einkauf", id)

    private fun findWhere(lock: Boolean = false, predicate: () -> Op<Boolean>): List<Purchase> = Purchases
        .selectAll()
        .where(predicate)
        .orderBy(Purchases.purchaseDate to SortOrder.DESC_NULLS_LAST)
        .let { if (lock) it.forUpdate() else it }
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
