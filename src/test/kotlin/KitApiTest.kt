import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class KitApiTest {

    @Test
    fun `kits keep their groups in order and can be replaced`() = apiTest {
        val halbdach = createIndividual("Jurtenhalbdach")
        val kohtenblatt = createIndividual("Kohtenblatt")
        val hering = createBulk("Hering")

        val roof = NewKitGroup(
            name = "Dach",
            requiredUnits = 6,
            options = listOf(NewKitGroupOption(kohtenblatt.id, priority = 1), NewKitGroupOption(halbdach.id, 3))
        )
        val pegs = NewKitGroup(requiredUnits = 30, options = listOf(NewKitGroupOption(hering.id)))
        val kit: Kit = post("/kits") { setBody(NewKit("Jurte", groups = listOf(roof, pegs))) }
            .expect(HttpStatusCode.Created)

        assertEquals(listOf("Dach", null), kit.groups.map { it.name })
        assertEquals(listOf(halbdach.id, kohtenblatt.id), kit.groups[0].options.map { it.materialId }, "by priority")

        val windows = NewKitGroup(requiredUnits = 8, isOptional = true, options = listOf(NewKitGroupOption(kohtenblatt.id)))
        val updated: Kit = put("/kits/${kit.id}") { setBody(NewKit("Jurte", groups = listOf(windows, roof))) }
            .expect(HttpStatusCode.OK)
        assertEquals(listOf(null, "Dach"), updated.groups.map { it.name })

        assertEquals(HttpStatusCode.NoContent, delete("/kits/${kit.id}").status)
        get("/kits/${kit.id}").expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `invalid groups and unknown materials are rejected`() = apiTest {
        val message = post("/kits") { setBody("""{"name": "Jurte", "groups": [{"requiredUnits": 1, "options": []}]}""") }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Jedes Teil braucht mindestens ein Material.", message)

        val unknown = Uuid.random()
        val group = NewKitGroup(requiredUnits = 1, options = listOf(NewKitGroupOption(unknown)))
        val unknownMessage = post("/kits") { setBody(NewKit("Jurte", groups = listOf(group))) }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Material $unknown gibt es nicht.", unknownMessage)

        val empty = post("/kits") { setBody("""{"name": "Jurte", "groups": []}""") }.expectError(HttpStatusCode.BadRequest)
        assertEquals("Ein Set braucht mindestens ein Teil.", empty)
    }
}
