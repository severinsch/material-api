import api.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** Names and categories as users type them, and messages about things that no longer exist. */
class NamesAndMessagesApiTest {

    private suspend fun HttpClient.createMaterial(material: NewMaterial): Material =
        post("materials") { setBody(material) }.expect(HttpStatusCode.Created)

    private fun bulk(name: String, category: String? = null): NewMaterial =
        NewBulkMaterial(name, category, variants = listOf(NewBulkVariant(availableCount = 1)))

    /** Changes the category of a bulk material with a single variant. */
    private suspend fun HttpClient.recategorize(material: Material, category: String): Material {
        val update = NewBulkMaterial(material.name, category, variants = listOf(NewBulkVariant(material.variantId, availableCount = 1)))
        return put("materials/${material.id}") { setBody<NewMaterial>(update) }.expect(HttpStatusCode.OK)
    }

    @Test
    fun `names are trimmed, so they can't get past the uniqueness checks`() = apiTest {
        assertEquals("Seil", createBulk(" Seil\t").name)
        val taken = post("materials") { setBody(bulk("Seil ")) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Es gibt schon ein Material mit diesem Namen.", taken)
        // The length limit applies to the trimmed name. As JSON: the request models check it when they are created.
        post("materials") {
            setBody("""{"trackingType": "BULK", "name": "${"x".repeat(255)}  ", "variants": [{"availableCount": 1}]}""")
        }.expect<Material>(HttpStatusCode.Created)

        val latz = listOf(NewIndividualVariant(name = "Latz"), NewIndividualVariant(name = "Latz "))
        val variants = post("materials") { setBody<NewMaterial>(NewIndividualMaterial("Kohtenplane", variants = latz)) }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Die Varianten eines Materials brauchen unterschiedliche Namen.", variants)

        val blatt = createIndividual("Kohtenblatt")
        assertEquals("K01", createItem(blatt, " K01 ").identifier)
        val identifier = post("items") { setBody(NewIndividualItem(blatt.variantId, "K01 ", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenblatt K01 gibt es schon.", identifier)

        assertEquals("Keller", createLocation("Keller ").name)
        post("locations") { setBody(NewStorageLocation(" Keller")) }.expectError(HttpStatusCode.Conflict)

        val amounts = listOf(NewKitGroupAmount(" mit", 1), NewKitGroupAmount("ohne", 0))
        val group = NewKitGroup(" Dach", amounts, listOf(NewKitGroupOption(blatt.id)))
        val kit: Kit = post("kits") { setBody(NewKit("Kohte ", groups = listOf(group))) }.expect(HttpStatusCode.Created)
        assertEquals(Triple("Kohte", "Dach", "mit"), Triple(kit.name, kit.groups[0].name, kit.groups[0].amounts[0].label))

        val newLoan = NewLoan(" Stamm Ost ", LocalDate(2026, 10, 1), itemIds = listOf(createItem(blatt, "K02").id))
        val loan: Loan = post("loans") { setBody(newLoan) }.expect(HttpStatusCode.Created)
        assertEquals("Stamm Ost", loan.borrower)
    }

    @Test
    fun `categories are trimmed and a blank one means none`() = apiTest {
        assertEquals("Zelte", createMaterial(bulk("Kohte", " Zelte ")).category)
        assertNull(createMaterial(bulk("Hering", "  ")).category)
        assertNull(createMaterial(bulk("Seil", "")).category)
    }

    @Test
    fun `categories are stored as given`() = apiTest {
        createMaterial(bulk("Kohte", "Zelte"))
        assertEquals("zelte", createMaterial(bulk("Jurte", "zelte")).category)

        // Changing a material's category changes no other material.
        val hering = createMaterial(bulk("Hering", "Zelte"))
        assertEquals("ZELTE", recategorize(hering, "ZELTE").category)
        val categories = get("materials").expect<List<Material>>(HttpStatusCode.OK).associate { it.name to it.category }
        assertEquals(mapOf("Kohte" to "Zelte", "Jurte" to "zelte", "Hering" to "ZELTE"), categories)
    }

    @Test
    fun `messages about missing things name them without ids`() = apiTest {
        val unknown = Uuid.random()
        for ((path, what) in listOf(
            "materials" to "Dieses Material", "items" to "Diesen Gegenstand", "kits" to "Dieses Set",
            "locations" to "Diesen Lagerort", "loans" to "Diese Ausleihe", "purchases" to "Diesen Einkauf",
            "damages" to "Diese Schadensmeldung",
        )) {
            val message = get("$path/$unknown").expectError(HttpStatusCode.NotFound)
            assertEquals("$what gibt es nicht mehr. Lade die Seite neu.", message)
        }
        assertEquals(
            "Dieses Material gibt es nicht mehr. Lade die Seite neu.",
            delete("materials/$unknown").expectError(HttpStatusCode.NotFound)
        )

        val gone = "Ein ausgewähltes Material gibt es nicht mehr, oder es wurde geändert. Lade die Seite neu."
        val purchase = post("purchases") { setBody(NewPurchase(unknown, quantity = 1)) }.expectError(HttpStatusCode.BadRequest)
        assertEquals(gone, purchase)
        assertEquals(
            gone,
            post("loans") { setBody(NewLoan("Stamm Ost", LocalDate(2026, 10, 1), materials = listOf(LoanQuantity(unknown, 1)))) }
                .expectError(HttpStatusCode.BadRequest)
        )
        assertEquals(
            "Einen der ausgewählten Gegenstände gibt es nicht mehr. Lade die Seite neu.",
            post("loans") { setBody(NewLoan("Stamm Ost", LocalDate(2026, 10, 1), itemIds = listOf(unknown))) }
                .expectError(HttpStatusCode.BadRequest)
        )
        assertEquals(
            "Diesen Gegenstand gibt es nicht mehr. Lade die Seite neu.",
            post("damages") { setBody(NewDamageReport(unknown, "Riss")) }.expectError(HttpStatusCode.BadRequest)
        )
        val blatt = createIndividual()
        assertEquals(
            "Den ausgewählten Lagerort gibt es nicht mehr. Lade die Seite neu.",
            post("items") { setBody(NewIndividualItem(blatt.variantId, "1", ItemCondition.GOOD, storageLocationId = unknown)) }
                .expectError(HttpStatusCode.BadRequest)
        )
    }
}
