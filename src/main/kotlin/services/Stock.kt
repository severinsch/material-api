package services

import database.BulkStock
import database.LoanLines
import database.Loans
import database.Materials
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// Stock of bulk materials. Must be called inside a transaction.

/** Locks the stock row of a bulk material until the transaction ends and returns its available quantity. */
internal fun lockStock(materialId: Uuid): Int = BulkStock
    .select(BulkStock.quantityAvailable)
    .where { BulkStock.materialId eq materialId }
    .forUpdate()
    .singleOrNull()?.get(BulkStock.quantityAvailable)
    ?: notFound("Material", materialId)

/** Pieces of a bulk material on loans that haven't been returned yet. The stock can't be lower than this. */
internal fun lentQuantity(materialId: Uuid): Int {
    val lent = LoanLines.quantity.sum()
    return LoanLines.innerJoin(Loans)
        .select(lent)
        .where { Loans.returnedAt.isNull() and (LoanLines.bulkMaterialId eq materialId) }
        .single()[lent] ?: 0
}

internal fun materialName(id: Uuid): String =
    Materials.select(Materials.name).where { Materials.id eq id }.single()[Materials.name]
