import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialApiTest {

    @Test
    fun `bulk material includes its stock and location`() = apiTest {
        val keller = createLocation("Keller")
        val created = createBulk("Hering", available = 40, location = keller)

        val material = getMaterial(created.id) as BulkMaterial
        assertEquals(40, material.availableCount)
        assertEquals(keller, material.location)
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

        val bulk: List<Material> = get("/materials?type=bulk").expect(HttpStatusCode.OK)
        assertEquals(listOf("Hering"), bulk.map { it.name })
        get("/materials?type=other").expectError(HttpStatusCode.BadRequest)

        val items: List<IndividualItem> = get("/items?materialId=${kohtenblatt.id}").expect(HttpStatusCode.OK)
        assertEquals(listOf(kohtenblatt.id), items.map { it.materialId })
    }

    @Test
    fun `the type of a material can't be changed`() = apiTest {
        val material = createIndividual()

        put("/materials/${material.id}") { setBody<NewMaterial>(NewBulkMaterial("Renamed", availableCount = 1)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenblatt", getMaterial(material.id).name)

        val renamed = put("/materials/${material.id}") { setBody<NewMaterial>(NewIndividualMaterial("Renamed")) }
            .expect<Material>(HttpStatusCode.OK)
        assertEquals("Renamed", renamed.name)
    }

    @Test
    fun `items can only belong to individual materials and stay there`() = apiTest {
        val bulk = createBulk()
        val message = post("/items") { setBody(NewIndividualItem(bulk.id, "1", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Material ${bulk.id} has tracking type BULK, expected INDIVIDUAL", message)

        val item = createItem(createIndividual("Kohtenblatt"), "1")
        val other = createIndividual("Jurtenhalbdach")
        put("/items/${item.id}") { setBody(NewIndividualItem(other.id, "1", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.BadRequest)
    }

    @Test
    fun `materials still in use can't be deleted`() = apiTest {
        val material = createIndividual()
        createItem(material, "1")

        val message = delete("/materials/${material.id}").expectError(HttpStatusCode.Conflict)
        assertFalse("violates" in message, "no database details in messages")

        val unused = createBulk()
        assertEquals(HttpStatusCode.NoContent, delete("/materials/${unused.id}").status)
        get("/materials/${unused.id}").expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `deleting a location keeps the material`() = apiTest {
        val keller = createLocation()
        val material = createBulk(location = keller)

        assertEquals(HttpStatusCode.NoContent, delete("/locations/${keller.id}").status)
        assertNull((getMaterial(material.id) as BulkMaterial).location)
    }

    @Test
    fun `invalid input is rejected with a message`() = apiTest {
        val blankName = post("/materials") { setBody("""{"trackingType": "INDIVIDUAL", "name": "  "}""") }
        assertTrue("name" in blankName.expectError(HttpStatusCode.BadRequest))

        val longName = post("/materials") { setBody("""{"trackingType": "INDIVIDUAL", "name": "${"x".repeat(256)}"}""") }
        assertTrue("at most 255" in longName.expectError(HttpStatusCode.BadRequest))

        get("/materials/not-a-uuid").expectError(HttpStatusCode.BadRequest)

        createIndividual("Kohtenblatt")
        post("/materials") { setBody<NewMaterial>(NewIndividualMaterial("Kohtenblatt")) }
            .expectError(HttpStatusCode.Conflict)
    }

    @Test
    fun `prices are validated and purchases are only recorded for bulk materials`() = apiTest {
        val bulk = createBulk()
        val individual = createIndividual()

        post("/purchases") { setBody(NewPurchase(bulk.id, quantity = 20, unitPrice = 0.5)) }
            .expect<Purchase>(HttpStatusCode.Created)
        post("/purchases") { setBody("""{"materialId": "${bulk.id}", "quantity": 1, "unitPrice": 0.333}""") }
            .expectError(HttpStatusCode.BadRequest)
        post("/purchases") { setBody(NewPurchase(individual.id, quantity = 1)) }
            .expectError(HttpStatusCode.BadRequest)

        val purchases: List<Purchase> = get("/purchases?materialId=${bulk.id}").expect(HttpStatusCode.OK)
        assertEquals(listOf(20), purchases.map { it.quantity })
    }
}
