import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.uuid.Uuid

class LoanAndDamageApiTest {

    private val lentAt = LocalDate(2026, 10, 1)

    @Test
    fun `an item can't be lent twice at the same time`() = apiTest {
        val material = createIndividual()
        val item = createItem(material, "1")
        val loan = NewLoan("Stamm Nord", lentAt, itemIds = listOf(item.id))

        val created: Loan = post("/loans") { setBody(loan) }.expect(HttpStatusCode.Created)
        assertEquals(1, getMaterial(material.id).loanedCount)
        assertTrue(get("/items/${item.id}").expect<IndividualItem>(HttpStatusCode.OK).onLoan)
        val message = post("/loans") { setBody(loan) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenblatt 1 is already on loan", message)

        // Editing the open loan itself doesn't conflict with its own lines.
        put("/loans/${created.id}") { setBody(loan.copy(note = "Pfingstlager")) }.expect<Loan>(HttpStatusCode.OK)

        put("/loans/${created.id}") { setBody(loan.copy(returnedAt = LocalDate(2026, 10, 5))) }
            .expect<Loan>(HttpStatusCode.OK)
        assertEquals(0, getMaterial(material.id).loanedCount)
        post("/loans") { setBody(loan) }.expect<Loan>(HttpStatusCode.Created)

        val open: List<Loan> = get("/loans?open=true").expect(HttpStatusCode.OK)
        assertEquals(1, open.size)
    }

    @Test
    fun `only active items can be lent and unknown references are rejected`() = apiTest {
        val material = createIndividual()
        val lost = createItem(material, "1", ItemCondition.POOR, ItemStatus.LOST)

        val message = post("/loans") { setBody(NewLoan("Stamm Nord", lentAt, itemIds = listOf(lost.id))) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenblatt 1 can't be lent because it is lost", message)

        val unknownItem = NewLoan("Stamm Nord", lentAt, itemIds = listOf(Uuid.random()))
        post("/loans") { setBody(unknownItem) }.expectError(HttpStatusCode.BadRequest)
        put("/loans/${Uuid.random()}") { setBody(unknownItem) }.expectError(HttpStatusCode.NotFound)

        val notBulk = NewLoan("Stamm Nord", lentAt, bulkMaterials = listOf(BulkLoanLine(material.id, 1)))
        post("/loans") { setBody(notBulk) }.expectError(HttpStatusCode.BadRequest)
    }

    @Test
    fun `only available pieces count as loaned`() = apiTest {
        val material = createIndividual()
        val good = createItem(material, "1")
        val poor = createItem(material, "2", ItemCondition.POOR)
        post("/loans") { setBody(NewLoan("Stamm Nord", lentAt, itemIds = listOf(good.id, poor.id))) }
            .expect<Loan>(HttpStatusCode.Created)

        val counts = getMaterial(material.id)
        assertEquals(1, counts.availableCount)
        assertEquals(1, counts.loanedCount, "the POOR item isn't part of availableCount")
    }

    @Test
    fun `bulk loans are limited by the stock`() = apiTest {
        val hering = createBulk(available = 50)
        fun loanOf(quantity: Int) = NewLoan("Stamm Nord", lentAt, bulkMaterials = listOf(BulkLoanLine(hering.id, quantity)))

        post("/loans") { setBody(loanOf(30)) }.expect<Loan>(HttpStatusCode.Created)
        val message = post("/loans") { setBody(loanOf(21)) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Only 20 of Hering available, 21 requested", message)
        post("/loans") { setBody(loanOf(20)) }.expect<Loan>(HttpStatusCode.Created)

        assertEquals(50, getMaterial(hering.id).loanedCount)
    }

    @Test
    fun `resolving a damage report keeps the original resolution time`() = apiTest {
        val item = createItem(createIndividual(), "1")
        val report: DamageReport = post("/damages") { setBody(NewDamageReport(item.id, "Riss")) }
            .expect(HttpStatusCode.Created)
        assertNull(report.resolvedAt)

        val resolve = NewDamageReport(item.id, "Riss", isResolved = true, resolutionNotes = "genäht")
        val resolved: DamageReport = put("/damages/${report.id}") { setBody(resolve) }.expect(HttpStatusCode.OK)
        assertNotNull(resolved.resolvedAt)

        val edited: DamageReport = put("/damages/${report.id}") { setBody(resolve.copy(description = "Langer Riss")) }
            .expect(HttpStatusCode.OK)
        assertEquals(resolved.resolvedAt, edited.resolvedAt)

        // The item of a report can't be changed.
        val otherItem = createItem(createIndividual("Jurtenhalbdach"), "1")
        put("/damages/${report.id}") { setBody(resolve.copy(itemId = otherItem.id)) }.expectError(HttpStatusCode.BadRequest)

        // Items with history can't be deleted, only retired via their status.
        delete("/items/${item.id}").expectError(HttpStatusCode.Conflict)
    }
}
