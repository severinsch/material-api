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
        assertEquals("Kohtenblatt 1 ist schon ausgeliehen.", message)

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
    fun `a loan stays editable when one of its items is damaged`() = apiTest {
        val material = createIndividual()
        val item = createItem(material, "1")
        val other = createItem(material, "2")
        val loan = NewLoan("Stamm Nord", lentAt, itemIds = listOf(item.id))
        val created: Loan = post("/loans") { setBody(loan) }.expect(HttpStatusCode.Created)
        post("/damages") { setBody(NewDamageReport(item.id, "Riss", itemStatus = ItemStatus.IN_REPAIR)) }
            .expect<DamageReport>(HttpStatusCode.Created)

        put("/loans/${created.id}") { setBody(loan.copy(note = "Pfingstlager")) }.expect<Loan>(HttpStatusCode.OK)

        // Adding a damaged item is still rejected.
        put("/items/${other.id}") {
            setBody(NewIndividualItem(material.id, "2", ItemCondition.GOOD, ItemStatus.IN_REPAIR))
        }.expect<IndividualItem>(HttpStatusCode.OK)
        val message = put("/loans/${created.id}") { setBody(loan.copy(itemIds = listOf(item.id, other.id))) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenblatt 2 ist in Reparatur und kann nicht ausgeliehen werden.", message)
    }

    @Test
    fun `only active items can be lent and unknown references are rejected`() = apiTest {
        val material = createIndividual()
        val lost = createItem(material, "1", ItemCondition.POOR, ItemStatus.LOST)

        val message = post("/loans") { setBody(NewLoan("Stamm Nord", lentAt, itemIds = listOf(lost.id))) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenblatt 1 ist verloren und kann nicht ausgeliehen werden.", message)

        val unknownItem = NewLoan("Stamm Nord", lentAt, itemIds = listOf(Uuid.random()))
        post("/loans") { setBody(unknownItem) }.expectError(HttpStatusCode.BadRequest)
        put("/loans/${Uuid.random()}") { setBody(unknownItem) }.expectError(HttpStatusCode.NotFound)

        val unknownMaterial = NewLoan("Stamm Nord", lentAt, materials = listOf(LoanQuantity(Uuid.random(), 1)))
        post("/loans") { setBody(unknownMaterial) }.expectError(HttpStatusCode.BadRequest)
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
        fun loanOf(quantity: Int) = NewLoan("Stamm Nord", lentAt, materials = listOf(LoanQuantity(hering.id, quantity)))

        post("/loans") { setBody(loanOf(30)) }.expect<Loan>(HttpStatusCode.Created)
        val message = post("/loans") { setBody(loanOf(21)) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Von Hering sind nur 20 verfügbar, nicht 21.", message)
        post("/loans") { setBody(loanOf(20)) }.expect<Loan>(HttpStatusCode.Created)

        assertEquals(50, getMaterial(hering.id).loanedCount)
    }

    @Test
    fun `individual materials can be lent by quantity`() = apiTest {
        val material = createIndividual()
        val (k1, k2) = listOf("1", "2", "3").map { createItem(material, it) }
        createItem(material, "4", ItemCondition.POOR)
        fun quantity(n: Int) = listOf(LoanQuantity(material.id, n))

        post("/loans") { setBody(NewLoan("Stamm Nord", lentAt, itemIds = listOf(k1.id))) }.expect<Loan>(HttpStatusCode.Created)
        val tooMany = post("/loans") { setBody(NewLoan("Stamm Süd", lentAt, materials = quantity(3))) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Von Kohtenblatt sind nur 2 verfügbar, nicht 3.", tooMany)
        val anyTwo = NewLoan("Stamm Süd", lentAt, materials = quantity(2))
        val created: Loan = post("/loans") { setBody(anyTwo) }.expect(HttpStatusCode.Created)
        assertEquals(quantity(2), created.materials)
        assertEquals(3, getMaterial(material.id).loanedCount)
        assertFalse(get("/items/${k2.id}").expect<IndividualItem>(HttpStatusCode.OK).onLoan, "which pieces is unknown")

        // All available pieces are out, so no specific item is left either.
        val noneLeft = post("/loans") { setBody(NewLoan("Stamm West", lentAt, itemIds = listOf(k2.id))) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Von Kohtenblatt sind nur 0 verfügbar, nicht 1.", noneLeft)

        // A piece breaks while lent out: the loan stays editable, but can't grow.
        put("/items/${k2.id}") { setBody(NewIndividualItem(material.id, "2", ItemCondition.GOOD, ItemStatus.IN_REPAIR)) }
            .expect<IndividualItem>(HttpStatusCode.OK)
        put("/loans/${created.id}") { setBody(anyTwo.copy(note = "Pfingstlager")) }.expect<Loan>(HttpStatusCode.OK)
        put("/loans/${created.id}") { setBody(anyTwo.copy(materials = quantity(3))) }.expectError(HttpStatusCode.Conflict)

        // Returned, the pieces count as available again.
        put("/loans/${created.id}") { setBody(anyTwo.copy(returnedAt = lentAt)) }.expect<Loan>(HttpStatusCode.OK)
        assertEquals(1, getMaterial(material.id).loanedCount)

        val mixed = NewLoan("Stamm West", lentAt, itemIds = listOf(createItem(material, "5").id), materials = quantity(1))
        val message = post("/loans") { setBody(mixed) }.expectError(HttpStatusCode.BadRequest)
        assertEquals("Gib für Kohtenblatt entweder eine Anzahl oder bestimmte Gegenstände an.", message)
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
        assertTrue(get("/items/${item.id}").expect<IndividualItem>(HttpStatusCode.OK).hasHistory)
        val message = delete("/items/${item.id}").expectError(HttpStatusCode.Conflict)
        assertEquals("Der Gegenstand hat Schadensmeldungen. Setze stattdessen seinen Status auf „Ausgemustert“.", message)
    }

    @Test
    fun `a damage report can set the item's status`() = apiTest {
        val item = createItem(createIndividual(), "1")
        suspend fun status() = get("/items/${item.id}").expect<IndividualItem>(HttpStatusCode.OK).status

        val report: DamageReport = post("/damages") { setBody(NewDamageReport(item.id, "Riss")) }
            .expect(HttpStatusCode.Created)
        assertEquals(ItemStatus.ACTIVE, status(), "reports alone don't change the item")

        post("/damages") { setBody(NewDamageReport(item.id, "Stange gebrochen", itemStatus = ItemStatus.IN_REPAIR)) }
            .expect<DamageReport>(HttpStatusCode.Created)
        assertEquals(ItemStatus.IN_REPAIR, status())

        put("/damages/${report.id}") { setBody(NewDamageReport(item.id, "Riss", isResolved = true, itemStatus = ItemStatus.ACTIVE)) }
            .expect<DamageReport>(HttpStatusCode.OK)
        assertEquals(ItemStatus.ACTIVE, status())
    }
}
