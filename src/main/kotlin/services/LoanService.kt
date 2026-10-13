package services

import api.BulkLoanLine
import api.Loan
import api.NewLoan
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

class LoanService(private val db: Db) {

    /** All loans, or only open (not yet returned) / only returned ones. */
    suspend fun getAll(open: Boolean? = null): List<Loan> = db.query {
        findWhere {
            when (open) {
                null -> Op.TRUE
                true -> Loans.returnedAt.isNull()
                false -> Loans.returnedAt.isNotNull()
            }
        }
    }

    suspend fun get(id: Uuid): Loan = db.query { find(id) }

    suspend fun create(loan: NewLoan): Loan = db.query {
        if (loan.returnedAt == null) checkAvailability(loan, excludeLoan = null)
        val id = Loans.insertAndGetId { it.fill(loan) }
        insertLines(id, loan)
        find(id.value)
    }

    /** Replaces the loan including all of its lines. Returning a loan means setting `returnedAt`. */
    suspend fun update(id: Uuid, loan: NewLoan): Loan = db.query {
        if (loan.returnedAt == null) checkAvailability(loan, excludeLoan = id)
        if (Loans.update({ Loans.id eq id }) { it.fill(loan) } == 0) notFound("Loan", id)
        LoanLines.deleteWhere { loanId eq id }
        insertLines(EntityID(id, Loans), loan)
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (Loans.deleteWhere { Loans.id eq id } == 0) notFound("Loan", id)
    }

    /** Ensures an open [loan] doesn't lend anything that other open loans already cover. */
    private fun checkAvailability(loan: NewLoan, excludeLoan: Uuid?) {
        val otherOpenLines = LoanLines.innerJoin(Loans)
        val isOtherOpenLoan = Loans.returnedAt.isNull() and (excludeLoan?.let { Loans.id neq it } ?: Op.TRUE)

        if (loan.itemIds.isNotEmpty()) {
            val alreadyLent = otherOpenLines
                .select(LoanLines.individualItemId)
                .where { isOtherOpenLoan and (LoanLines.individualItemId inList loan.itemIds) }
                .map { it[LoanLines.individualItemId]!!.value }
            if (alreadyLent.isNotEmpty()) throw ConflictException("Items already on loan: ${alreadyLent.joinToString()}")
        }

        if (loan.bulkMaterials.isNotEmpty()) {
            val materialIds = loan.bulkMaterials.map(BulkLoanLine::materialId)
            val lent = LoanLines.quantity.sum()
            val lentByMaterial = otherOpenLines
                .select(LoanLines.bulkMaterialId, lent)
                .where { isOtherOpenLoan and (LoanLines.bulkMaterialId inList materialIds) }
                .groupBy(LoanLines.bulkMaterialId)
                .associate { it[LoanLines.bulkMaterialId]!!.value to (it[lent] ?: 0) }
            val inStock = BulkStock
                .select(BulkStock.materialId, BulkStock.quantityAvailable)
                .where { BulkStock.materialId inList materialIds }
                .associate { it[BulkStock.materialId].value to it[BulkStock.quantityAvailable] }

            loan.bulkMaterials.forEach { line ->
                // Unknown materials are rejected by the foreign key when inserting the line.
                val stock = inStock[line.materialId] ?: return@forEach
                val free = stock - (lentByMaterial[line.materialId] ?: 0)
                if (line.quantity > free) {
                    throw ConflictException("Only $free of material ${line.materialId} available, ${line.quantity} requested")
                }
            }
        }
    }

    private fun insertLines(loanId: EntityID<Uuid>, loan: NewLoan) {
        LoanLines.batchInsert(loan.itemIds) { itemId ->
            this[LoanLines.loanId] = loanId
            this[LoanLines.individualItemId] = itemId
        }
        LoanLines.batchInsert(loan.bulkMaterials) { line ->
            this[LoanLines.loanId] = loanId
            this[LoanLines.bulkMaterialId] = line.materialId
            this[LoanLines.quantity] = line.quantity
        }
    }

    private fun find(id: Uuid): Loan = findWhere { Loans.id eq id }.singleOrNull() ?: notFound("Loan", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<Loan> {
        val loanRows = Loans.selectAll().where(predicate).orderBy(Loans.lentAt, SortOrder.DESC).toList()
        val linesByLoan = LoanLines
            .selectAll()
            .where { LoanLines.loanId inList loanRows.map { it[Loans.id] } }
            .orderBy(LoanLines.id)
            .groupBy { it[LoanLines.loanId].value }

        return loanRows.map { row ->
            val lines = linesByLoan[row[Loans.id].value].orEmpty()
            Loan(
                id = row[Loans.id].value,
                borrower = row[Loans.borrower],
                lentAt = row[Loans.lentAt],
                dueAt = row[Loans.dueAt],
                returnedAt = row[Loans.returnedAt],
                note = row[Loans.note],
                itemIds = lines.mapNotNull { it[LoanLines.individualItemId]?.value },
                bulkMaterials = lines.mapNotNull { line ->
                    line[LoanLines.bulkMaterialId]?.let { BulkLoanLine(it.value, line[LoanLines.quantity]!!) }
                }
            )
        }
    }

    private fun UpdateBuilder<*>.fill(loan: NewLoan) {
        this[Loans.borrower] = loan.borrower
        this[Loans.lentAt] = loan.lentAt
        this[Loans.dueAt] = loan.dueAt
        this[Loans.returnedAt] = loan.returnedAt
        this[Loans.note] = loan.note
    }
}
