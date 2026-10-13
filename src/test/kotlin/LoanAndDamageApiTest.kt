import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlin.test.*

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
        post("/loans") { setBody(loan) }.expectError(HttpStatusCode.Conflict)

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
    fun `bulk loans are limited by the stock`() = apiTest {
        val hering = createBulk(available = 50)
        fun loanOf(quantity: Int) = NewLoan("Stamm Nord", lentAt, bulkMaterials = listOf(BulkLoanLine(hering.id, quantity)))

        post("/loans") { setBody(loanOf(30)) }.expect<Loan>(HttpStatusCode.Created)
        post("/loans") { setBody(loanOf(21)) }.expectError(HttpStatusCode.Conflict)
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

        // Items with history can't be deleted, only retired via their status.
        delete("/items/${item.id}").expectError(HttpStatusCode.Conflict)
    }
}
