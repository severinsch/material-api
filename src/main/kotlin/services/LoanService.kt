package services

import api.ItemStatus
import api.Loan
import api.LoanQuantity
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
        checkLines(loan, existing = null)
        val id = Loans.insertAndGetId { it.fill(loan) }
        insertLines(id, loan)
        find(id.value)
    }

    /** Replaces the loan including all of its lines. Returning a loan means setting `returnedAt`. */
    suspend fun update(id: Uuid, loan: NewLoan): Loan = db.query {
        checkLines(loan, existing = find(id))
        Loans.update({ Loans.id eq id }) { it.fill(loan) }
        LoanLines.deleteWhere { loanId eq id }
        insertLines(EntityID(id, Loans), loan)
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (Loans.deleteWhere { Loans.id eq id } == 0) notFound("Ausleihe", id)
    }

    /**
     * Checks that everything lent exists and, for an open loan, is available: specific items must be active and on no
     * other open loan, and the pieces per material (quantities plus specific available items) must not exceed what
     * other open loans leave. Parts of an open loan that don't change aren't checked again, so it stays editable when
     * e.g. one of its items was damaged while lent out.
     */
    private fun checkLines(loan: NewLoan, existing: Loan?) {
        val previous = existing?.takeIf { it.returnedAt == null }
        val items = IndividualItems
            .select(IndividualItems.id, IndividualItems.materialId, IndividualItems.status, isAvailableItem)
            .where { IndividualItems.id inList (loan.itemIds + previous?.itemIds.orEmpty()) }
            .forUpdate()
            .associateBy { it[IndividualItems.id].value }
        loan.itemIds.forEach { if (it !in items) invalid("Gegenstand $it gibt es nicht.") }
        requireMaterials(loan.materials.map(LoanQuantity::materialId))
        val itemMaterials = loan.itemIds.map { items.getValue(it)[IndividualItems.materialId].value }.toSet()
        loan.materials.firstOrNull { it.materialId in itemMaterials }?.let {
            invalid("Gib für ${materialName(it.materialId)} entweder eine Anzahl oder bestimmte Gegenstände an.")
        }
        if (loan.returnedAt != null) return

        val newItems = loan.itemIds - previous?.itemIds.orEmpty().toSet()
        newItems.firstOrNull { items.getValue(it)[IndividualItems.status] != ItemStatus.ACTIVE }?.let {
            val status = when (items.getValue(it)[IndividualItems.status]) {
                ItemStatus.ACTIVE -> "aktiv"
                ItemStatus.IN_REPAIR -> "in Reparatur"
                ItemStatus.LOST -> "verloren"
                ItemStatus.DECOMMISSIONED -> "ausgemustert"
            }
            conflict("${itemName(it)} ist $status und kann nicht ausgeliehen werden.")
        }
        LoanLines.innerJoin(Loans)
            .select(LoanLines.individualItemId)
            .where { Loans.returnedAt.isNull() and (LoanLines.individualItemId inList newItems) }
            .firstOrNull()
            ?.let { conflict("${itemName(it[LoanLines.individualItemId]!!.value)} ist schon ausgeliehen.") }

        val before = previous?.let { pieces(it.itemIds, it.materials, items) }.orEmpty()
        val increased = pieces(loan.itemIds, loan.materials, items).filter { (id, n) -> n > (before[id] ?: 0) }
        lockMaterials(increased.keys)
        val available = availableCounts(increased.keys)
        val loaned = loanedCounts(increased.keys, exceptLoan = existing?.id)
        increased.forEach { (materialId, wanted) ->
            val free = maxOf(0, (available[materialId] ?: 0) - (loaned[materialId] ?: 0))
            if (wanted > free) conflict("Von ${materialName(materialId)} sind nur $free verfügbar, nicht $wanted.")
        }
    }

    /** Pieces per material that count against availability: [quantities], and those of [itemIds] that are available. */
    private fun pieces(itemIds: List<Uuid>, quantities: List<LoanQuantity>, items: Map<Uuid, ResultRow>): Map<Uuid, Int> {
        val pieces = quantities.associate { it.materialId to it.quantity }.toMutableMap()
        itemIds.map(items::getValue).filter { it[isAvailableItem] }.forEach {
            pieces.merge(it[IndividualItems.materialId].value, 1, Int::plus)
        }
        return pieces
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
        LoanLines.batchInsert(loan.materials) { line ->
            this[LoanLines.loanId] = loanId
            this[LoanLines.materialId] = line.materialId
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
                materials = lines.mapNotNull { line ->
                    line[LoanLines.materialId]?.let { LoanQuantity(it.value, line[LoanLines.quantity]!!) }
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
