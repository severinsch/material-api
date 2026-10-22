package services

import api.ItemCondition
import api.ItemStatus
import database.BulkStock
import database.IndividualItems
import database.LoanLines
import database.Loans
import database.MaterialVariants
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// What is available and what open loans hold, per material variant, for loans and stock changes. Changes that affect
// availability (loans, purchases, stock edits) lock the rows of the affected variants first, so they are checked one
// after another. Must be called inside a transaction.

/** Individual items that count as available: active and not in POOR condition. */
internal val isAvailableItem: Op<Boolean>
    get() = (IndividualItems.status eq ItemStatus.ACTIVE) and (IndividualItems.condition neq ItemCondition.POOR)

/** Locks the rows of [variantIds] in `material_variants` until the transaction ends, in a fixed order to avoid deadlocks. */
internal fun lockVariants(variantIds: Collection<Uuid>) {
    if (variantIds.isEmpty()) return
    MaterialVariants.select(MaterialVariants.id)
        .where { MaterialVariants.id inList variantIds }
        .orderBy(MaterialVariants.id)
        .forUpdate()
        .toList()
}

/** Locks bulk variants (see [lockVariants]) and returns their available stock. */
internal fun lockStock(variantIds: Collection<Uuid>): Map<Uuid, Int> {
    lockVariants(variantIds)
    return BulkStock.select(BulkStock.variantId, BulkStock.quantityAvailable)
        .where { BulkStock.variantId inList variantIds }
        .associate { it[BulkStock.variantId].value to it[BulkStock.quantityAvailable] }
}

/** Locks a bulk variant (see [lockVariants]) and returns its available stock. */
internal fun lockStock(variantId: Uuid): Int = lockStock(listOf(variantId))[variantId] ?: notFound("Variante", variantId)

/** Available pieces per variant: the stock of bulk variants, the available items of individual ones. */
internal fun availableCounts(variantIds: Collection<Uuid>): Map<Uuid, Int> {
    val stock = BulkStock.select(BulkStock.variantId, BulkStock.quantityAvailable)
        .where { BulkStock.variantId inList variantIds }
        .associate { it[BulkStock.variantId].value to it[BulkStock.quantityAvailable] }
    val items = IndividualItems.id.count()
    val availableItems = IndividualItems.select(IndividualItems.variantId, items)
        .where { (IndividualItems.variantId inList variantIds) and isAvailableItem }
        .groupBy(IndividualItems.variantId)
        .associate { it[IndividualItems.variantId].value to it[items].toInt() }
    return stock + availableItems
}

/**
 * Available pieces per variant held by loans that haven't been returned yet: quantities, and specific items that
 * count as available (others, e.g. in POOR condition, aren't part of the available count either). So
 * `available - loaned` is what's in storage. Only for [variantIds] if given, and without the loan [exceptLoan].
 */
internal fun loanedCounts(variantIds: Collection<Uuid>? = null, exceptLoan: Uuid? = null): Map<Uuid, Int> {
    val variantId = Coalesce(LoanLines.variantId, IndividualItems.variantId)
    val pieces = Coalesce(LoanLines.quantity, intLiteral(1)).sum()
    return LoanLines.innerJoin(Loans).leftJoin(IndividualItems)
        .select(variantId, pieces)
        .where {
            listOfNotNull(
                Loans.returnedAt.isNull(),
                LoanLines.variantId.isNotNull() or isAvailableItem,
                variantIds?.let { (LoanLines.variantId inList it) or (IndividualItems.variantId inList it) },
                exceptLoan?.let { Loans.id neq it }
            ).compoundAnd()
        }
        .groupBy(variantId)
        .associate { it[variantId].value to (it[pieces] ?: 0) }
}

/** Available pieces of one variant on open loans; the stock can't be lower than this. */
internal fun loanedCount(variantId: Uuid): Int = loanedCounts(listOf(variantId))[variantId] ?: 0
