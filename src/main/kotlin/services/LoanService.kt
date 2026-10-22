package services

import api.ItemStatus
import api.Loan
import api.LoanQuantity
import api.NewLoan
import auth.Permission
import auth.TenantAccess
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

/**
 * Users with [Permission.EDIT] manage all loans. Users with only [Permission.BORROW] lend to themselves, and see and
 * change only their own open loans; other loans don't exist for them (404).
 */
class LoanService(private val db: Db) {

    /** All loans the user may see, or only open (not yet returned) / only returned ones. */
    suspend fun getAll(access: TenantAccess, open: Boolean? = null): List<Loan> = db.tenantQuery(access.tenant) {
        findWhere {
            visibleTo(access) and when (open) {
                null -> Op.TRUE
                true -> Loans.returnedAt.isNull()
                false -> Loans.returnedAt.isNotNull()
            }
        }
    }

    suspend fun get(access: TenantAccess, id: Uuid): Loan = db.tenantQuery(access.tenant) { find(access, id) }

    suspend fun create(access: TenantAccess, loan: NewLoan): Loan = db.tenantQuery(access.tenant) {
        val borrower = borrowerName(access, loan, existing = null)
        checkLines(loan, existing = null)
        val id = Loans.insertAndGetId { it.fill(loan, borrower) }
        insertLines(id, loan)
        find(access, id.value)
    }

    /** Replaces the loan including all of its lines. Returning a loan means setting `returnedAt`. */
    suspend fun update(access: TenantAccess, id: Uuid, loan: NewLoan): Loan = db.tenantQuery(access.tenant) {
        val existing = find(access, id)
        if (existing.returnedAt != null && !access.permits(Permission.EDIT)) {
            forbidden("Eine zurückgegebene Ausleihe kannst du nicht mehr ändern.")
        }
        val borrower = borrowerName(access, loan, existing)
        checkLines(loan, existing)
        Loans.update({ Loans.id eq id }) { it.fill(loan, borrower) }
        LoanLines.deleteWhere { loanId eq id }
        insertLines(EntityID(id, Loans), loan)
        find(access, id)
    }

    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        if (Loans.deleteWhere { Loans.id eq id } == 0) notFound("Ausleihe", id)
    }

    private fun visibleTo(access: TenantAccess): Op<Boolean> =
        if (access.permits(Permission.EDIT)) Op.TRUE else Loans.borrowerId eq access.user.userId

    /**
     * The borrower's name to store. A borrowing member must be a member of the tenant, unless they already are the
     * loan's borrower (e.g. after leaving the tenant). Without [Permission.EDIT], users can only borrow for themselves.
     */
    private fun borrowerName(access: TenantAccess, loan: NewLoan, existing: Loan?): String {
        val borrowerId = loan.borrowerId
        if (borrowerId != access.user.userId && !access.permits(Permission.EDIT)) {
            forbidden("Du kannst nur für dich selbst ausleihen.")
        }
        // Without a member, NewLoan requires the name.
        if (borrowerId == null) return loan.borrower!!
        val isMember = borrowerId == existing?.borrowerId || TenantMembers.selectAll()
            .where { (TenantMembers.tenantId eq access.tenant.value) and (TenantMembers.userId eq borrowerId) }
            .any()
        if (!isMember) invalid("Die ausleihende Person ist kein Mitglied dieses Stamms.")
        return Users.select(Users.displayName).where { Users.id eq borrowerId }.single()[Users.displayName]
    }

    /**
     * Checks that everything lent exists and, for an open loan, is available: specific items must be active and on no
     * other open loan, and the pieces per variant (quantities plus specific available items) must not exceed what
     * other open loans leave. Parts of an open loan that don't change aren't checked again, so it stays editable when
     * e.g. one of its items was damaged while lent out.
     */
    private fun checkLines(loan: NewLoan, existing: Loan?) {
        val previous = existing?.takeIf { it.returnedAt == null }
        val items = IndividualItems
            .select(IndividualItems.id, IndividualItems.variantId, IndividualItems.status, isAvailableItem)
            .where { IndividualItems.id inList (loan.itemIds + previous?.itemIds.orEmpty()) }
            .forUpdate()
            .associateBy { it[IndividualItems.id].value }
        loan.itemIds.forEach { if (it !in items) invalid("Gegenstand $it gibt es nicht.") }
        requireVariants(loan.materials.map(LoanQuantity::variantId))
        val itemVariants = loan.itemIds.map { items.getValue(it)[IndividualItems.variantId].value }.toSet()
        loan.materials.firstOrNull { it.variantId in itemVariants }?.let {
            invalid("Gib für ${variantName(it.variantId)} entweder eine Anzahl oder bestimmte Gegenstände an.")
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
        lockVariants(increased.keys)
        val available = availableCounts(increased.keys)
        val loaned = loanedCounts(increased.keys, exceptLoan = existing?.id)
        increased.forEach { (variantId, wanted) ->
            val free = maxOf(0, (available[variantId] ?: 0) - (loaned[variantId] ?: 0))
            if (wanted > free) conflict("Von ${variantName(variantId)} sind nur $free verfügbar, nicht $wanted.")
        }
    }

    /** Pieces per variant that count against availability: [quantities], and those of [itemIds] that are available. */
    private fun pieces(itemIds: List<Uuid>, quantities: List<LoanQuantity>, items: Map<Uuid, ResultRow>): Map<Uuid, Int> {
        val pieces = quantities.associate { it.variantId to it.quantity }.toMutableMap()
        itemIds.map(items::getValue).filter { it[isAvailableItem] }.forEach {
            pieces.merge(it[IndividualItems.variantId].value, 1, Int::plus)
        }
        return pieces
    }

    private fun insertLines(loanId: EntityID<Uuid>, loan: NewLoan) {
        LoanLines.batchInsert(loan.itemIds) { itemId ->
            this[LoanLines.loanId] = loanId
            this[LoanLines.individualItemId] = itemId
        }
        LoanLines.batchInsert(loan.materials) { line ->
            this[LoanLines.loanId] = loanId
            this[LoanLines.variantId] = line.variantId
            this[LoanLines.quantity] = line.quantity
        }
    }

    private fun find(access: TenantAccess, id: Uuid): Loan =
        findWhere { (Loans.id eq id) and visibleTo(access) }.singleOrNull() ?: notFound("Ausleihe", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<Loan> {
        val loanRows = Loans.leftJoin(Users).selectAll().where(predicate).orderBy(Loans.lentAt, SortOrder.DESC).toList()
        val linesByLoan = LoanLines
            .selectAll()
            .where { LoanLines.loanId inList loanRows.map { it[Loans.id] } }
            .orderBy(LoanLines.id)
            .groupBy { it[LoanLines.loanId].value }

        return loanRows.map { row ->
            val lines = linesByLoan[row[Loans.id].value].orEmpty()
            Loan(
                id = row[Loans.id].value,
                borrower = row.getOrNull(Users.displayName) ?: row[Loans.borrower],
                borrowerId = row[Loans.borrowerId]?.value,
                lentAt = row[Loans.lentAt],
                dueAt = row[Loans.dueAt],
                returnedAt = row[Loans.returnedAt],
                note = row[Loans.note],
                itemIds = lines.mapNotNull { it[LoanLines.individualItemId]?.value },
                materials = lines.mapNotNull { line ->
                    line[LoanLines.variantId]?.let { LoanQuantity(it.value, line[LoanLines.quantity]!!) }
                }
            )
        }
    }

    private fun UpdateBuilder<*>.fill(loan: NewLoan, borrower: String) {
        this[Loans.borrower] = borrower
        this[Loans.borrowerId] = loan.borrowerId
        this[Loans.lentAt] = loan.lentAt
        this[Loans.dueAt] = loan.dueAt
        this[Loans.returnedAt] = loan.returnedAt
        this[Loans.note] = loan.note
    }
}
