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
            amounts = units(6),
            options = listOf(NewKitGroupOption(kohtenblatt.id, priority = 1), NewKitGroupOption(halbdach.id, unitValue = 3))
        )
        val pegs = NewKitGroup(amounts = units(30), options = listOf(NewKitGroupOption(hering.id)))
        val kit: Kit = post("kits") { setBody(NewKit("Jurte", groups = listOf(roof, pegs))) }
            .expect(HttpStatusCode.Created)

        assertEquals(listOf("Dach", null), kit.groups.map { it.name })
        assertEquals(listOf(halbdach.id, kohtenblatt.id), kit.groups[0].options.map { it.materialId }, "by priority")

        val windows = NewKitGroup(
            amounts = listOf(NewKitGroupAmount("ohne", 0), NewKitGroupAmount("mit", 8)),
            options = listOf(NewKitGroupOption(kohtenblatt.id))
        )
        val updated: Kit = put("kits/${kit.id}") { setBody(NewKit("Jurte", groups = listOf(windows, roof))) }
            .expect(HttpStatusCode.OK)
        assertEquals(listOf(null, "Dach"), updated.groups.map { it.name })

        assertEquals(HttpStatusCode.NoContent, delete("kits/${kit.id}").status)
        get("kits/${kit.id}").expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `groups keep their amounts in order`() = apiTest {
        val stange = createIndividual("Jurtenstange")
        val fenster = createIndividual("Fensterplane")
        val pole = NewKitGroup(
            name = "Mittelstange",
            amounts = listOf(NewKitGroupAmount("Einbein", 1), NewKitGroupAmount("Dreibein", 3)),
            options = listOf(NewKitGroupOption(stange.id))
        )
        val windows = NewKitGroup(
            amounts = listOf(NewKitGroupAmount("mit", 12), NewKitGroupAmount("ohne", 0)),
            options = listOf(NewKitGroupOption(fenster.id))
        )
        val single = NewKitGroup(amounts = units(4), options = listOf(NewKitGroupOption(stange.id)))
        val kit: Kit = post("kits") { setBody(NewKit("Jurte", groups = listOf(pole, windows, single))) }
            .expect(HttpStatusCode.Created)

        assertEquals(
            listOf(
                listOf(KitGroupAmount("Einbein", 1), KitGroupAmount("Dreibein", 3)),
                listOf(KitGroupAmount("mit", 12), KitGroupAmount("ohne", 0)),
                listOf(KitGroupAmount(null, 4)),
            ),
            kit.groups.map { it.amounts }
        )
        assertEquals(kit, get("kits/${kit.id}").expect<Kit>(HttpStatusCode.OK))
    }

    @Test
    fun `invalid amounts are rejected`() = apiTest {
        val material = createIndividual()
        val options = """[{"materialId": "${material.id}"}]"""
        suspend fun messageFor(amounts: String) = post("kits") {
            setBody("""{"name": "Jurte", "groups": [{"amounts": $amounts, "options": $options}]}""")
        }.expectError(HttpStatusCode.BadRequest)

        assertEquals("Jedes Teil braucht mindestens eine Ausführung.", messageFor("[]"))
        assertEquals(
            "Mindestens eine Ausführung eines Teils braucht Einheiten.",
            messageFor("""[{"label": "ohne", "requiredUnits": 0}, {"label": "auch ohne", "requiredUnits": 0}]""")
        )
        assertEquals(
            "Hat ein Teil mehrere Ausführungen, braucht jede eine Bezeichnung.",
            messageFor("""[{"requiredUnits": 1}, {"label": "Dreibein", "requiredUnits": 3}]""")
        )
        assertEquals(
            "Die Ausführungen eines Teils brauchen unterschiedliche Bezeichnungen.",
            messageFor("""[{"label": "Einbein", "requiredUnits": 1}, {"label": "Einbein", "requiredUnits": 3}]""")
        )
        assertEquals(
            "Die Bezeichnung einer Ausführung darf nicht leer sein.",
            messageFor("""[{"label": " ", "requiredUnits": 1}, {"label": "Dreibein", "requiredUnits": 3}]""")
        )
        assertEquals(
            "Die Bezeichnung einer Ausführung darf höchstens 100 Zeichen lang sein.",
            messageFor("""[{"label": "${"x".repeat(101)}", "requiredUnits": 1}]""")
        )
        assertEquals("Die Anzahl der Einheiten darf nicht negativ sein.", messageFor("""[{"requiredUnits": -1}]"""))
    }

    @Test
    fun `invalid groups and unknown materials are rejected`() = apiTest {
        val message = post("kits") {
            setBody("""{"name": "Jurte", "groups": [{"amounts": [{"requiredUnits": 1}], "options": []}]}""")
        }.expectError(HttpStatusCode.BadRequest)
        assertEquals("Jedes Teil braucht mindestens ein Material.", message)

        val unknown = Uuid.random()
        val group = NewKitGroup(amounts = units(1), options = listOf(NewKitGroupOption(unknown)))
        val unknownMessage = post("kits") { setBody(NewKit("Jurte", groups = listOf(group))) }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Material $unknown gibt es nicht.", unknownMessage)

        val empty = post("kits") { setBody("""{"name": "Jurte", "groups": []}""") }.expectError(HttpStatusCode.BadRequest)
        assertEquals("Ein Set braucht mindestens ein Teil.", empty)
    }

    @Test
    fun `options refer to a material or to one of its variants`() = apiTest {
        val seil = createBulk(
            "Seil",
            NewBulkVariant(name = "10 m", availableCount = 4),
            NewBulkVariant(name = "15 m", availableCount = 6),
            NewBulkVariant(name = "20 m", availableCount = 2)
        )
        val (_, fifteenMeters, twentyMeters) = seil.variants
        val hering = createBulk("Hering")
        // At least 15 m: the two longer variants. Any variant of Hering.
        val longRopes = NewKitGroup(
            amounts = units(2),
            options = listOf(NewKitGroupOption(seil.id, twentyMeters.id), NewKitGroupOption(seil.id, fifteenMeters.id))
        )
        val pegs = NewKitGroup(amounts = units(30), options = listOf(NewKitGroupOption(hering.id)))
        val kit: Kit = post("kits") { setBody(NewKit("Jurte", groups = listOf(longRopes, pegs))) }
            .expect(HttpStatusCode.Created)

        assertEquals(
            setOf(KitGroupOption(seil.id, fifteenMeters.id, 1, 0), KitGroupOption(seil.id, twentyMeters.id, 1, 0)),
            kit.groups[0].options.toSet()
        )
        assertEquals(listOf(KitGroupOption(hering.id, null, 1, 0)), kit.groups[1].options)
        assertEquals(kit, get("kits/${kit.id}").expect<Kit>(HttpStatusCode.OK))
    }

    @Test
    fun `options of a group don't overlap and variants belong to their material`() = apiTest {
        val seil = createBulk("Seil", NewBulkVariant(name = "10 m", availableCount = 4), NewBulkVariant(name = "15 m", availableCount = 6))
        val (tenMeters, fifteenMeters) = seil.variants
        val hering = createBulk("Hering")
        // As JSON: the request models reject overlapping options when they are created.
        suspend fun messageFor(vararg options: Pair<Uuid, Uuid?>) = post("kits") {
            val json = options.joinToString { (material, variant) ->
                """{"materialId": "$material"${variant?.let { """, "variantId": "$it"""" }.orEmpty()}}"""
            }
            setBody("""{"name": "Jurte", "groups": [{"amounts": [{"requiredUnits": 1}], "options": [$json]}]}""")
        }.expectError(HttpStatusCode.BadRequest)

        val overlapping = "Die Materialien eines Teils überschneiden sich: Nimm ein Material entweder einmal mit " +
            "beliebiger Variante auf oder mit unterschiedlichen, bestimmten Varianten."
        assertEquals(overlapping, messageFor(seil.id to null, seil.id to tenMeters.id))
        assertEquals(overlapping, messageFor(seil.id to tenMeters.id, seil.id to tenMeters.id))
        assertEquals(overlapping, messageFor(seil.id to null, seil.id to null))

        assertEquals(
            "Die Variante ${tenMeters.id} gehört nicht zu Material ${hering.id}.",
            messageFor(hering.id to tenMeters.id)
        )
        val unknown = Uuid.random()
        assertEquals("Variante $unknown gibt es nicht.", messageFor(seil.id to unknown))

        // The same material in another group is fine.
        val groups = listOf(
            NewKitGroup(amounts = units(1), options = listOf(NewKitGroupOption(seil.id))),
            NewKitGroup(amounts = units(1), options = listOf(NewKitGroupOption(seil.id, fifteenMeters.id))),
        )
        post("kits") { setBody(NewKit("Jurte", groups = groups)) }.expect<Kit>(HttpStatusCode.Created)
    }

    private fun units(requiredUnits: Int) = listOf(NewKitGroupAmount(requiredUnits = requiredUnits))
}
