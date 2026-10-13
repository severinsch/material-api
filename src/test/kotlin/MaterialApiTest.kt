import api.*
import database.ItemCondition
import database.ItemStatus
import io.ktor.client.request.*
import io.ktor.http.*
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
    fun `materials can be filtered by type`() = apiTest {
        createBulk("Hering")
        createIndividual("Kohtenblatt")

        val bulk: List<Material> = get("/materials?type=bulk").expect(HttpStatusCode.OK)
        assertEquals(listOf("Hering"), bulk.map { it.name })
        get("/materials?type=other").expectError(HttpStatusCode.BadRequest)
    }

    @Test
    fun `updating a material through the endpoint of the other type is not found`() = apiTest {
        val material = createIndividual()

        put("/materials/bulk/${material.id}") { setBody(NewBulkMaterial("Renamed", availableCount = 1)) }
            .expectError(HttpStatusCode.NotFound)
        assertEquals("Kohtenblatt", getMaterial(material.id).name)
    }

    @Test
    fun `items can only belong to individual materials`() = apiTest {
        val bulk = createBulk()

        post("/items") { setBody(NewIndividualItem(bulk.id, "1", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.Conflict)
    }

    @Test
    fun `materials still in use can't be deleted`() = apiTest {
        val material = createIndividual()
        createItem(material, "1")

        delete("/materials/${material.id}").expectError(HttpStatusCode.Conflict)

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
        val blankName = post("/materials/individual") { setBody("""{"name": "  "}""") }
        assertTrue("name" in blankName.expectError(HttpStatusCode.BadRequest))

        get("/materials/not-a-uuid").expectError(HttpStatusCode.BadRequest)

        createIndividual("Kohtenblatt")
        post("/materials/individual") { setBody(NewIndividualMaterial("Kohtenblatt")) }
            .expectError(HttpStatusCode.Conflict)
    }

    @Test
    fun `purchases are only recorded for bulk materials`() = apiTest {
        val bulk = createBulk()
        val individual = createIndividual()

        post("/purchases") { setBody(NewPurchase(bulk.id, quantity = 20, unitPrice = 0.5)) }
            .expect<Purchase>(HttpStatusCode.Created)
        post("/purchases") { setBody(NewPurchase(individual.id, quantity = 1)) }
            .expectError(HttpStatusCode.Conflict)

        val purchases: List<Purchase> = get("/purchases?material_id=${bulk.id}").expect(HttpStatusCode.OK)
        assertEquals(listOf(20), purchases.map { it.quantity })
    }
}
