package services

import api.BulkLoanLine
import api.Loan
import api.ItemStatus
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
        checkLines(loan, loanId = null)
        val id = Loans.insertAndGetId { it.fill(loan) }
        insertLines(id, loan)
        find(id.value)
    }

    /** Replaces the loan including all of its lines. Returning a loan means setting `returnedAt`. */
    suspend fun update(id: Uuid, loan: NewLoan): Loan = db.query {
        find(id)
        checkLines(loan, loanId = id)
        Loans.update({ Loans.id eq id }) { it.fill(loan) }
        LoanLines.deleteWhere { loanId eq id }
        insertLines(EntityID(id, Loans), loan)
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (Loans.deleteWhere { Loans.id eq id } == 0) notFound("Ausleihe", id)
    }

    /**
     * Checks that everything lent exists and, for an open loan, is available: items must be active and not on another
     * open loan, bulk quantities must not exceed the stock minus other open loans. Items already on this loan may have
     * another status, e.g. when one was damaged while lent out. The checked rows are locked until the transaction
     * ends, so concurrent loans can't both pass the check.
     */
    private fun checkLines(loan: NewLoan, loanId: Uuid?) {
        val statuses = IndividualItems
            .select(IndividualItems.id, IndividualItems.status)
            .where { IndividualItems.id inList loan.itemIds }
            .forUpdate()
            .associate { it[IndividualItems.id].value to it[IndividualItems.status] }
        val stock = BulkStock
            .select(BulkStock.materialId, BulkStock.quantityAvailable)
            .where { BulkStock.materialId inList loan.bulkMaterials.map(BulkLoanLine::materialId) }
            .forUpdate()
            .associate { it[BulkStock.materialId].value to it[BulkStock.quantityAvailable] }
        loan.itemIds.forEach { if (it !in statuses) invalid("Gegenstand $it gibt es nicht.") }
        loan.bulkMaterials.forEach {
            if (it.materialId !in stock) invalid("Material ${it.materialId} gibt es nicht oder wird nicht nach Menge erfasst.")
        }
        if (loan.returnedAt != null) return

        val alreadyLent = loanId?.let { id ->
            LoanLines.select(LoanLines.individualItemId)
                .where { (LoanLines.loanId eq id) and LoanLines.individualItemId.isNotNull() }
                .map { it[LoanLines.individualItemId]!!.value }
                .toSet()
        }.orEmpty()
        statuses.filter { (id, status) -> status != ItemStatus.ACTIVE && id !in alreadyLent }.keys.firstOrNull()?.let {
            val status = when (statuses.getValue(it)) {
                ItemStatus.ACTIVE -> "aktiv"
                ItemStatus.IN_REPAIR -> "in Reparatur"
                ItemStatus.LOST -> "verloren"
                ItemStatus.DECOMMISSIONED -> "ausgemustert"
            }
            conflict("${itemName(it)} ist $status und kann nicht ausgeliehen werden.")
        }
        val otherOpenLines = LoanLines.innerJoin(Loans)
        val isOtherOpenLoan = Loans.returnedAt.isNull() and (loanId?.let { Loans.id neq it } ?: Op.TRUE)
        otherOpenLines
            .select(LoanLines.individualItemId)
            .where { isOtherOpenLoan and (LoanLines.individualItemId inList loan.itemIds) }
            .firstOrNull()
            ?.let { conflict("${itemName(it[LoanLines.individualItemId]!!.value)} ist schon ausgeliehen.") }

        val lent = LoanLines.quantity.sum()
        val lentByMaterial = otherOpenLines
            .select(LoanLines.bulkMaterialId, lent)
            .where { isOtherOpenLoan and (LoanLines.bulkMaterialId inList stock.keys) }
            .groupBy(LoanLines.bulkMaterialId)
            .associate { it[LoanLines.bulkMaterialId]!!.value to (it[lent] ?: 0) }
        loan.bulkMaterials.forEach { line ->
            val free = stock.getValue(line.materialId) - (lentByMaterial[line.materialId] ?: 0)
            if (line.quantity > free) {
                conflict("Von ${materialName(line.materialId)} sind nur $free verfügbar, nicht ${line.quantity}.")
            }
        }
    }

    // Names for conflict messages, which are shown to users.

    private fun itemName(id: Uuid): String = IndividualItems.innerJoin(Materials)
        .select(Materials.name, IndividualItems.identifier)
        .where { IndividualItems.id eq id }
        .single()
        .let { "${it[Materials.name]} ${it[IndividualItems.identifier]}" }

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

    private fun find(id: Uuid): Loan = findWhere { Loans.id eq id }.singleOrNull() ?: notFound("Ausleihe", id)

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
