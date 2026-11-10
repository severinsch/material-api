import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArchiveApiTest {

    private val lentAt = LocalDate(2026, 10, 1)

    @Test
    fun `archived materials are still listed and can be taken out of the archive`() = apiTest {
        val hering = createBulk("Hering")
        assertNull(hering.archivedAt)

        val archived = post("materials/${hering.id}/archive").expect<Material>(HttpStatusCode.OK)
        val archivedAt = assertNotNull(archived.archivedAt)
        val listed: List<Material> = get("materials").expect(HttpStatusCode.OK)
        assertEquals(listOf(archivedAt), listed.map { it.archivedAt })
        // Archiving again keeps the time.
        assertEquals(archivedAt, post("materials/${hering.id}/archive").expect<Material>(HttpStatusCode.OK).archivedAt)

        assertNull(post("materials/${hering.id}/unarchive").expect<Material>(HttpStatusCode.OK).archivedAt)
        assertNull(post("materials/${hering.id}/unarchive").expect<Material>(HttpStatusCode.OK).archivedAt)
        assertNull(getMaterial(hering.id).archivedAt)
    }

    @Test
    fun `only editors archive materials`() = apiTest { context ->
        val hering = createBulk()
        val viewer = context.clientFor(TenantRole.VIEWER)

        viewer.post("materials/${hering.id}/archive").expectError(HttpStatusCode.Forbidden)
        post("materials/${hering.id}/archive").expect<Material>(HttpStatusCode.OK)
        viewer.post("materials/${hering.id}/unarchive").expectError(HttpStatusCode.Forbidden)
        assertNotNull(viewer.get("materials/${hering.id}").expect<Material>(HttpStatusCode.OK).archivedAt)
    }

    @Test
    fun `archiving only marks a material, except that it can't be merged`() = apiTest {
        val hering = createBulk("Hering", available = 20)
        val blatt = createIndividual("Kohtenblatt")
        val item = createItem(blatt, "1")
        val loan = NewLoan("Stamm Ost", lentAt, itemIds = listOf(item.id), materials = listOf(LoanQuantity(hering.variantId, 4)))
        val open: Loan = post("loans") { setBody(loan) }.expect(HttpStatusCode.Created)
        for (material in listOf(hering, blatt)) {
            post("materials/${material.id}/archive").expect<Material>(HttpStatusCode.OK)
        }

        // Clients hide archived materials where new use starts; the API doesn't refuse it.
        post("purchases") { setBody(NewPurchase(hering.variantId, quantity = 5)) }.expect<Purchase>(HttpStatusCode.Created)
        post("items") { setBody(NewIndividualItem(blatt.variantId, "2", ItemCondition.NEW)) }
            .expect<IndividualItem>(HttpStatusCode.Created)
        put("loans/${open.id}") { setBody(loan.copy(returnedAt = LocalDate(2026, 10, 3))) }.expect<Loan>(HttpStatusCode.OK)
        val pegs = NewKitGroup(amounts = listOf(NewKitGroupAmount(requiredUnits = 30)), options = listOf(NewKitGroupOption(hering.id)))
        post("kits") { setBody(NewKit("Jurte", groups = listOf(pegs))) }.expect<Kit>(HttpStatusCode.Created)

        val other = createIndividual("Kohtenblatt alt")
        // Neither as a source nor as the target.
        for (ids in listOf(listOf(other.id, blatt.id), listOf(blatt.id, other.id))) {
            assertEquals(
                "Kohtenblatt ist archiviert. Hole das Material zuerst aus dem Archiv, um es zusammenzuführen.",
                post("materials/merge") { setBody(MaterialMerge(ids, "Kohtenblatt")) }.expectError(HttpStatusCode.Conflict)
            )
        }
    }

    @Test
    fun `archived materials can still get variants and stock changes`() = apiTest {
        val seil = createBulk("Seil", available = 10)
        post("materials/${seil.id}/archive").expect<Material>(HttpStatusCode.OK)

        val update = NewBulkMaterial(
            "Seil",
            variants = listOf(
                NewBulkVariant(seil.variantId, name = "10 m", availableCount = 6),
                NewBulkVariant(name = "15 m", availableCount = 2)
            )
        )
        val updated = put("materials/${seil.id}") { setBody<NewMaterial>(update) }.expect<Material>(HttpStatusCode.OK)
        assertEquals(listOf(6, 2), (updated as BulkMaterial).variants.map { it.availableCount })
        assertNotNull(updated.archivedAt)
    }

    @Test
    fun `materials used in kits can't be archived`() = apiTest {
        val hering = createBulk()
        val pegs = NewKitGroup(amounts = listOf(NewKitGroupAmount(requiredUnits = 30)), options = listOf(NewKitGroupOption(hering.id)))
        val kit: Kit = post("kits") { setBody(NewKit("Jurte", groups = listOf(pegs))) }.expect(HttpStatusCode.Created)

        assertEquals(
            "Das Material wird im Set Jurte verwendet. Entferne es zuerst daraus.",
            post("materials/${hering.id}/archive").expectError(HttpStatusCode.Conflict)
        )
        assertNull(getMaterial(hering.id).archivedAt)

        delete("kits/${kit.id}")
        post("materials/${hering.id}/archive").expect<Material>(HttpStatusCode.OK)
    }

    @Test
    fun `deleting a material with history suggests archiving it`() = apiTest {
        suspend fun deleteError(material: Material) = delete("materials/${material.id}").expect<ErrorResponse>(HttpStatusCode.Conflict)
        val history = ErrorResponse(
            "Das Material hat Gegenstände, Einkäufe oder Ausleihen und kann deshalb nicht gelöscht werden.",
            ErrorCode.ARCHIVE_INSTEAD
        )

        val hering = createBulk("Hering")
        post("purchases") { setBody(NewPurchase(hering.variantId, quantity = 5)) }.expect<Purchase>(HttpStatusCode.Created)
        assertEquals(history, deleteError(hering))
        val seil = createBulk("Seil", available = 5)
        post("loans") { setBody(NewLoan("Stamm Ost", lentAt, materials = listOf(LoanQuantity(seil.variantId, 1)))) }
            .expect<Loan>(HttpStatusCode.Created)
        assertEquals(history, deleteError(seil))
        val blatt = createIndividual("Kohtenblatt")
        createItem(blatt, "1")
        assertEquals(history, deleteError(blatt))

        // Archiving doesn't help with kits, so there is no code.
        val stange = createBulk("Stange")
        val poles = NewKitGroup(amounts = listOf(NewKitGroupAmount(requiredUnits = 4)), options = listOf(NewKitGroupOption(stange.id)))
        post("kits") { setBody(NewKit("Jurte", groups = listOf(poles))) }.expect<Kit>(HttpStatusCode.Created)
        assertEquals(ErrorResponse("Das Material wird in einem Set verwendet. Entferne es zuerst aus dem Set."), deleteError(stange))

        // An archived material gets the same code; clients don't offer archiving it again.
        post("materials/${hering.id}/archive").expect<Material>(HttpStatusCode.OK)
        assertEquals(history, deleteError(hering))

        // An archived material can be deleted once nothing references it.
        val unused = createBulk("Zeltbahn")
        post("materials/${unused.id}/archive").expect<Material>(HttpStatusCode.OK)
        assertEquals(HttpStatusCode.NoContent, delete("materials/${unused.id}").status)
        assertEquals(HttpStatusCode.NotFound, delete("materials/${unused.id}").status)
    }
}
