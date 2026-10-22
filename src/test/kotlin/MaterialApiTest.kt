import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialApiTest {

    @Test
    fun `bulk material includes its stock and location`() = apiTest {
        val keller = createLocation("Keller")
        val created = createBulk("Hering", available = 40, location = keller)

        val material = getMaterial(created.id) as BulkMaterial
        assertEquals(40, material.availableCount)
        assertEquals(keller, material.variants.single().location)
    }

    @Test
    fun `individual counts follow condition and status`() = apiTest {
        val material = createIndividual()
        createItem(material, "1", ItemCondition.NEW)
        createItem(material, "2", ItemCondition.FAIR)
        createItem(material, "3", ItemCondition.POOR)
        createItem(material, "4", ItemCondition.GOOD, ItemStatus.IN_REPAIR)
        createItem(material, "5", ItemCondition.GOOD, ItemStatus.DECOMMISSIONED)
        createItem(material, "6", ItemCondition.POOR, ItemStatus.LOST)

        val counts = getMaterial(material.id)
        assertEquals(2, counts.availableCount)
        assertEquals(2, counts.damagedCount)
    }

    @Test
    fun `materials and items can be filtered`() = apiTest {
        createBulk("Hering")
        val kohtenblatt = createIndividual("Kohtenblatt")
        createItem(kohtenblatt, "1")
        createItem(createIndividual("Jurtenhalbdach"), "1")

        val bulk: List<Material> = get("materials?type=bulk").expect(HttpStatusCode.OK)
        assertEquals(listOf("Hering"), bulk.map { it.name })
        get("materials?type=other").expectError(HttpStatusCode.BadRequest)

        val items: List<IndividualItem> = get("items?materialId=${kohtenblatt.id}").expect(HttpStatusCode.OK)
        assertEquals(listOf(kohtenblatt.id), items.map { it.materialId })
    }

    @Test
    fun `the type of a material can't be changed`() = apiTest {
        val material = createIndividual()

        val bulk = NewBulkMaterial("Renamed", variants = listOf(NewBulkVariant(material.variantId, availableCount = 1)))
        put("materials/${material.id}") { setBody<NewMaterial>(bulk) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenblatt", getMaterial(material.id).name)

        val individual = NewIndividualMaterial("Renamed", variants = listOf(NewIndividualVariant(material.variantId)))
        val renamed = put("materials/${material.id}") { setBody<NewMaterial>(individual) }.expect<Material>(HttpStatusCode.OK)
        assertEquals("Renamed", renamed.name)
    }

    @Test
    fun `items can only belong to individual materials and stay there`() = apiTest {
        val bulk = createBulk()
        val message = post("items") { setBody(NewIndividualItem(bulk.variantId, "1", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Hering wird nicht einzeln erfasst.", message)

        val item = createItem(createIndividual("Kohtenblatt"), "1")
        val other = createIndividual("Jurtenhalbdach")
        val moved = put("items/${item.id}") { setBody(NewIndividualItem(other.variantId, "1", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Das Material eines Gegenstands kann nicht geändert werden.", moved)
    }

    @Test
    fun `materials still in use can't be deleted`() = apiTest {
        val material = createIndividual()
        createItem(material, "1")

        val message = delete("materials/${material.id}").expectError(HttpStatusCode.Conflict)
        assertEquals("Das Material hat noch Gegenstände. Lösche zuerst die Gegenstände.", message)

        val unused = createBulk()
        assertEquals(HttpStatusCode.NoContent, delete("materials/${unused.id}").status)
        get("materials/${unused.id}").expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `deleting a location keeps the material`() = apiTest {
        val keller = createLocation()
        val material = createBulk(location = keller)

        assertEquals(HttpStatusCode.NoContent, delete("locations/${keller.id}").status)
        assertNull((getMaterial(material.id) as BulkMaterial).variants.single().location)
    }

    @Test
    fun `invalid input is rejected with a message`() = apiTest {
        val blankName = post("materials") { setBody("""{"trackingType": "INDIVIDUAL", "name": "  ", "variants": [{}]}""") }
        assertEquals("Der Name darf nicht leer sein.", blankName.expectError(HttpStatusCode.BadRequest))

        val longName = post("materials") {
            setBody("""{"trackingType": "INDIVIDUAL", "name": "${"x".repeat(256)}", "variants": [{}]}""")
        }
        assertEquals("Der Name darf höchstens 255 Zeichen lang sein.", longName.expectError(HttpStatusCode.BadRequest))

        get("materials/not-a-uuid").expectError(HttpStatusCode.BadRequest)

        // The variants are always the complete list, so a material without them is invalid, not one without changes.
        val withoutVariants = post("materials") { setBody("""{"trackingType": "INDIVIDUAL", "name": "Kohte"}""") }
        withoutVariants.expectError(HttpStatusCode.BadRequest)

        createIndividual("Kohtenblatt")
        val taken = post("materials") {
            setBody<NewMaterial>(NewIndividualMaterial("Kohtenblatt", variants = listOf(NewIndividualVariant())))
        }.expectError(HttpStatusCode.Conflict)
        assertEquals("Es gibt schon ein Material mit diesem Namen.", taken)
    }

    @Test
    fun `several items can be created at once with unique identifiers`() = apiTest {
        val material = createIndividual()
        createItem(material, "2")
        fun itemsOf(vararg identifiers: String) =
            identifiers.map { NewIndividualItem(material.variantId, it, ItemCondition.NEW) }

        val message = post("items/batch") { setBody(itemsOf("1", "2", "3", "3")) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenblatt 2, 3 gibt es schon.", message)
        val created: List<IndividualItem> = post("items/batch") { setBody(itemsOf("1", "3")) }
            .expect(HttpStatusCode.Created)
        assertEquals(listOf("1", "3"), created.map { it.identifier })
        assertTrue(created.none { it.hasHistory })
        assertEquals(3, getMaterial(material.id).availableCount)

        post("items/batch") { setBody(emptyList<NewIndividualItem>()) }.expectError(HttpStatusCode.BadRequest)
        // Renaming an item to a taken identifier is rejected, keeping its own identifier isn't.
        val item = created.first()
        put("items/${item.id}") { setBody(NewIndividualItem(material.variantId, "2", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.Conflict)
        put("items/${item.id}") { setBody(NewIndividualItem(material.variantId, "1", ItemCondition.GOOD)) }
            .expect<IndividualItem>(HttpStatusCode.OK)
    }

    @Test
    fun `prices are validated and purchases are only recorded for bulk materials`() = apiTest {
        val bulk = createBulk()
        val individual = createIndividual()

        post("purchases") { setBody(NewPurchase(bulk.variantId, quantity = 20, unitPrice = 0.5)) }
            .expect<Purchase>(HttpStatusCode.Created)
        post("purchases") { setBody("""{"variantId": "${bulk.variantId}", "quantity": 1, "unitPrice": 0.333}""") }
            .expectError(HttpStatusCode.BadRequest)
        post("purchases") { setBody(NewPurchase(individual.variantId, quantity = 1)) }
            .expectError(HttpStatusCode.BadRequest)

        val purchases: List<Purchase> = get("purchases?materialId=${bulk.id}").expect(HttpStatusCode.OK)
        assertEquals(listOf(20), purchases.map { it.quantity })
    }

    @Test
    fun `purchases add to the stock`() = apiTest {
        val hering = createBulk(available = 10)
        suspend fun stock() = getMaterial(hering.id).availableCount

        val purchase: Purchase = post("purchases") { setBody(NewPurchase(hering.variantId, quantity = 50)) }
            .expect(HttpStatusCode.Created)
        assertEquals(60, stock())
        put("purchases/${purchase.id}") { setBody(NewPurchase(hering.variantId, quantity = 40)) }
            .expect<Purchase>(HttpStatusCode.OK)
        assertEquals(50, stock())

        // Pegs got lost after the purchase, so it can't be undone completely.
        put("materials/${hering.id}") { setBody(stockUpdate(hering, 30)) }.expect<Material>(HttpStatusCode.OK)
        val message = delete("purchases/${purchase.id}").expectError(HttpStatusCode.Conflict)
        assertEquals("Von Hering sind 30 im Bestand. Es können nicht 40 abgezogen werden.", message)
        assertEquals(30, stock())

        put("materials/${hering.id}") { setBody(stockUpdate(hering, 45)) }.expect<Material>(HttpStatusCode.OK)
        assertEquals(HttpStatusCode.NoContent, delete("purchases/${purchase.id}").status)
        assertEquals(5, stock())
        delete("purchases/${purchase.id}").expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `the stock can't drop below the pieces lent out`() = apiTest {
        val hering = createBulk(available = 20)
        val purchase: Purchase = post("purchases") { setBody(NewPurchase(hering.variantId, quantity = 100)) }
            .expect(HttpStatusCode.Created)
        post("loans") {
            setBody(NewLoan("Stamm Nord", LocalDate(2026, 10, 1), materials = listOf(LoanQuantity(hering.variantId, 65))))
        }.expect<Loan>(HttpStatusCode.Created)

        val message = delete("purchases/${purchase.id}").expectError(HttpStatusCode.Conflict)
        assertEquals("Von Hering sind 120 im Bestand, davon 65 ausgeliehen. Es können nicht 100 abgezogen werden.", message)
        put("purchases/${purchase.id}") { setBody(NewPurchase(hering.variantId, quantity = 45)) }
            .expect<Purchase>(HttpStatusCode.OK)
        assertEquals(65, getMaterial(hering.id).availableCount)

        val tooLow = put("materials/${hering.id}") { setBody(stockUpdate(hering, 60)) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Von Hering sind 65 ausgeliehen, der Bestand kann nicht kleiner sein.", tooLow)
    }

    @Test
    fun `editing a material doesn't overwrite stock changed in the meantime`() = apiTest {
        val hering = createBulk(available = 10)
        post("purchases") { setBody(NewPurchase(hering.variantId, quantity = 50)) }.expect<Purchase>(HttpStatusCode.Created)

        val message = put("materials/${hering.id}") { setBody(stockUpdate(hering, 10, expected = 10)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals(
            "Der Bestand von Hering wurde inzwischen auf 60 geändert, z. B. durch einen Einkauf. " +
                "Lade die Seite neu und bearbeite das Material erneut.",
            message
        )
        put("materials/${hering.id}") { setBody(stockUpdate(hering, 58, expected = 60)) }.expect<Material>(HttpStatusCode.OK)
        assertEquals(58, getMaterial(hering.id).availableCount)
    }
}
