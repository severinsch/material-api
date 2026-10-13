import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals

class KitApiTest {

    @Test
    fun `kits keep their groups in order and can be replaced`() = apiTest {
        val halbdach = createIndividual("Jurtenhalbdach")
        val kohtenblatt = createIndividual("Kohtenblatt")
        val hering = createBulk("Hering")

        val roof = NewKitGroup(
            name = "Dach",
            requiredUnits = 6.0,
            options = listOf(KitGroupOption(halbdach.id, 3.0, priority = 0), KitGroupOption(kohtenblatt.id))
        )
        val pegs = NewKitGroup(requiredUnits = 30.0, options = listOf(KitGroupOption(hering.id)))
        val kit: Kit = post("/kits") { setBody(NewKit("Jurte", groups = listOf(roof, pegs))) }
            .expect(HttpStatusCode.Created)

        assertEquals(listOf("Dach", null), kit.groups.map { it.name })
        assertEquals(listOf(3.0, 1.0), kit.groups[0].options.map { it.unitValue })

        val windows = NewKitGroup(requiredUnits = 8.0, isOptional = true, options = listOf(KitGroupOption(kohtenblatt.id)))
        val updated: Kit = put("/kits/${kit.id}") { setBody(NewKit("Jurte", groups = listOf(windows))) }
            .expect(HttpStatusCode.OK)
        assertEquals(listOf(true), updated.groups.map { it.isOptional })

        assertEquals(HttpStatusCode.NoContent, delete("/kits/${kit.id}").status)
        get("/kits/${kit.id}").expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `groups need at least one option`() = apiTest {
        val message = post("/kits") { setBody("""{"name": "Jurte", "groups": [{"requiredUnits": 1, "options": []}]}""") }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("a group needs at least one option", message)
    }
}
