package services

import api.MaterialTrackingType
import api.NewPurchase
import api.Purchase
import database.BulkStock
import database.Db
import database.MaterialVariants
import database.TenantId
import database.Purchases
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

/** Purchases add to the stock of their variant: creating, changing and deleting one adjusts the stock. */
class PurchaseService(private val db: Db) {

    /** All purchases, or those of all variants of [materialId]. */
    suspend fun getAll(tenant: TenantId, materialId: Uuid? = null): List<Purchase> = db.tenantQuery(tenant) {
        findWhere { materialId?.let { MaterialVariants.materialId eq it } ?: Op.TRUE }
    }

    suspend fun get(tenant: TenantId, id: Uuid): Purchase = db.tenantQuery(tenant) { find(id) }

    suspend fun create(tenant: TenantId, purchase: NewPurchase): Purchase = db.tenantQuery(tenant) {
        requireVariant(purchase.variantId, MaterialTrackingType.BULK)
        val id = Purchases.insertAndGetId { it.fill(purchase) }
        adjustStock(purchase.variantId, purchase.quantity)
        find(id.value)
    }

    suspend fun update(tenant: TenantId, id: Uuid, purchase: NewPurchase): Purchase = db.tenantQuery(tenant) {
        val existing = find(id, lock = true)
        requireUnchanged("Die Variante eines Einkaufs", existing.variantId, purchase.variantId)
        Purchases.update({ Purchases.id eq id }) { it.fill(purchase) }
        adjustStock(purchase.variantId, purchase.quantity - existing.quantity)
        find(id)
    }

    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        val existing = find(id, lock = true)
        Purchases.deleteWhere { Purchases.id eq id }
        adjustStock(existing.variantId, -existing.quantity)
    }

    /**
     * Changes the available stock by [delta]. Rejects decreases below the pieces that are lent out, e.g. deleting a
     * purchase whose pieces were used up or are on a loan.
     */
    private fun adjustStock(variantId: Uuid, delta: Int) {
        if (delta == 0) return
        val available = lockStock(variantId)
        if (delta < 0) {
            val lent = loanedCount(variantId)
            if (available + delta < lent) {
                val lentNote = if (lent > 0) ", davon $lent ausgeliehen" else ""
                conflict(
                    "Von ${variantName(variantId)} sind $available im Bestand$lentNote. " +
                        "Es können nicht ${-delta} abgezogen werden."
                )
            }
        }
        BulkStock.update({ BulkStock.variantId eq variantId }) { it[quantityAvailable] = available + delta }
    }

    /**
     * With [lock], concurrent changes of the purchase (and of its variant's stock) wait until the transaction ends, so
     * the stock is adjusted once.
     */
    private fun find(id: Uuid, lock: Boolean = false): Purchase =
        findWhere(lock) { Purchases.id eq id }.singleOrNull() ?: notFound("Einkauf", id)

    private fun findWhere(lock: Boolean = false, predicate: () -> Op<Boolean>): List<Purchase> = Purchases
        .innerJoin(MaterialVariants)
        .select(Purchases.columns + MaterialVariants.materialId)
        .where(predicate)
        .orderBy(Purchases.purchaseDate to SortOrder.DESC_NULLS_LAST)
        .let { if (lock) it.forUpdate() else it }
        .map {
            Purchase(
                id = it[Purchases.id].value,
                materialId = it[MaterialVariants.materialId].value,
                variantId = it[Purchases.variantId].value,
                quantity = it[Purchases.quantity],
                unitPrice = it[Purchases.unitPrice]?.toDouble(),
                purchaseDate = it[Purchases.purchaseDate],
                note = it[Purchases.note]
            )
        }

    private fun UpdateBuilder<*>.fill(purchase: NewPurchase) {
        this[Purchases.variantId] = purchase.variantId
        this[Purchases.quantity] = purchase.quantity
        this[Purchases.unitPrice] = purchase.unitPrice?.toBigDecimal()
        this[Purchases.purchaseDate] = purchase.purchaseDate
        this[Purchases.note] = purchase.note
    }
}
