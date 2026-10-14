package services

import api.ItemCondition
import api.ItemStatus
import database.BulkStock
import database.IndividualItems
import database.LoanLines
import database.Loans
import database.Materials
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// What is available and what open loans hold, for loans and stock changes. Changes that affect availability (loans,
// purchases, stock edits) lock the rows of the affected materials first, so they are checked one after another. Must
// be called inside a transaction.

/** Individual items that count as available: active and not in POOR condition. */
internal val isAvailableItem: Op<Boolean>
    get() = (IndividualItems.status eq ItemStatus.ACTIVE) and (IndividualItems.condition neq ItemCondition.POOR)

/** Locks the rows of [materialIds] in `materials` until the transaction ends, in a fixed order to avoid deadlocks. */
internal fun lockMaterials(materialIds: Collection<Uuid>) {
    if (materialIds.isEmpty()) return
    Materials.select(Materials.id)
        .where { Materials.id inList materialIds }
        .orderBy(Materials.id)
        .forUpdate()
        .toList()
}

/** Locks a bulk material (see [lockMaterials]) and returns its available stock. */
internal fun lockStock(materialId: Uuid): Int {
    lockMaterials(listOf(materialId))
    return BulkStock.select(BulkStock.quantityAvailable)
        .where { BulkStock.materialId eq materialId }
        .singleOrNull()?.get(BulkStock.quantityAvailable)
        ?: notFound("Material", materialId)
}

/** Available pieces per material: the stock of bulk materials, the available items of individual ones. */
internal fun availableCounts(materialIds: Collection<Uuid>): Map<Uuid, Int> {
    val stock = BulkStock.select(BulkStock.materialId, BulkStock.quantityAvailable)
        .where { BulkStock.materialId inList materialIds }
        .associate { it[BulkStock.materialId].value to it[BulkStock.quantityAvailable] }
    val items = IndividualItems.id.count()
    val availableItems = IndividualItems.select(IndividualItems.materialId, items)
        .where { (IndividualItems.materialId inList materialIds) and isAvailableItem }
        .groupBy(IndividualItems.materialId)
        .associate { it[IndividualItems.materialId].value to it[items].toInt() }
    return stock + availableItems
}

/**
 * Available pieces per material held by loans that haven't been returned yet: quantities, and specific items that
 * count as available (others, e.g. in POOR condition, aren't part of the available count either). So
 * `available - loaned` is what's in storage. Only for [materialIds] if given, and without the loan [exceptLoan].
 */
internal fun loanedCounts(materialIds: Collection<Uuid>? = null, exceptLoan: Uuid? = null): Map<Uuid, Int> {
    val materialId = Coalesce(LoanLines.materialId, IndividualItems.materialId)
    val pieces = Coalesce(LoanLines.quantity, intLiteral(1)).sum()
    return LoanLines.innerJoin(Loans).leftJoin(IndividualItems)
        .select(materialId, pieces)
        .where {
            listOfNotNull(
                Loans.returnedAt.isNull(),
                LoanLines.materialId.isNotNull() or isAvailableItem,
                materialIds?.let { (LoanLines.materialId inList it) or (IndividualItems.materialId inList it) },
                exceptLoan?.let { Loans.id neq it }
            ).compoundAnd()
        }
        .groupBy(materialId)
        .associate { it[materialId].value to (it[pieces] ?: 0) }
}

/** Available pieces of one material on open loans; the stock can't be lower than this. */
internal fun loanedCount(materialId: Uuid): Int = loanedCounts(listOf(materialId))[materialId] ?: 0

internal fun materialName(id: Uuid): String =
    Materials.select(Materials.name).where { Materials.id eq id }.single()[Materials.name]
