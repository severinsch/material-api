import api.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Materials with several variants: stock, items, purchases and loans per variant. */
class VariantApiTest {

    private val lentAt = LocalDate(2026, 10, 1)

    @Test
    fun `variants have their own stock and location and are sorted by name`() = apiTest {
        val keller = createLocation("Keller")
        val seil = createBulk(
            "Seil",
            NewBulkVariant(name = "15 m", availableCount = 6, storageLocationId = keller.id, locationDetail = "Seilkiste"),
            NewBulkVariant(name = "5 m", availableCount = 2, damagedCount = 1),
            NewBulkVariant(name = "10 m", availableCount = 4),
        )

        assertEquals(listOf("5 m", "10 m", "15 m"), seil.variants.map { it.name })
        assertEquals(listOf(2, 4, 6), seil.variants.map { it.availableCount })
        assertEquals(listOf(null, null, keller), seil.variants.map { it.location })
        assertEquals("Seilkiste", seil.variants.last().locationDetail)
        assertEquals(12 to 1, seil.availableCount to seil.damagedCount)
        assertEquals(seil, getMaterial(seil.id))
    }

    @Test
    fun `variants are created, changed and removed with the material`() = apiTest {
        val seil = createSeil()
        val (tenMeters, fifteenMeters) = seil.variants

        val update = NewBulkMaterial(
            "Seil",
            variants = listOf(
                NewBulkVariant(tenMeters.id, name = "10 m", availableCount = 5, expectedAvailableCount = 4),
                NewBulkVariant(name = "20 m", availableCount = 3),
            ),
            removedVariantIds = listOf(fifteenMeters.id)
        )
        val updated = put("materials/${seil.id}") { setBody<NewMaterial>(update) }
            .expect<Material>(HttpStatusCode.OK) as BulkMaterial
        assertEquals(listOf("10 m" to 5, "20 m" to 3), updated.variants.map { it.name to it.availableCount })
        assertEquals(tenMeters.id, updated.variants.first().id)
        assertEquals(8, updated.availableCount)

        // Back to a single variant, which may then be unnamed.
        val single = NewBulkMaterial(
            "Seil",
            variants = listOf(NewBulkVariant(tenMeters.id, availableCount = 5)),
            removedVariantIds = listOf(updated.variants.last().id)
        )
        val renamed = put("materials/${seil.id}") { setBody<NewMaterial>(single) }
            .expect<Material>(HttpStatusCode.OK) as BulkMaterial
        assertEquals(listOf(tenMeters.id to null), renamed.variants.map { it.id to it.name })
    }

    @Test
    fun `names of variants can be swapped`() = apiTest {
        val plane = createKohtenplane()
        val (latz, ohne) = plane.variants
        val swapped = NewIndividualMaterial(
            "Kohtenplane",
            variants = listOf(NewIndividualVariant(latz.id, "ohne Latz"), NewIndividualVariant(ohne.id, "45 cm Latz"))
        )
        val updated = put("materials/${plane.id}") { setBody<NewMaterial>(swapped) }.expect<Material>(HttpStatusCode.OK)
            as IndividualMaterial
        assertEquals(listOf(ohne.id to "45 cm Latz", latz.id to "ohne Latz"), updated.variants.map { it.id to it.name })
    }

    @Test
    fun `a variant that is neither listed nor removed is a conflict`() = apiTest {
        val seil = createSeil()
        // E.g. an editor who loaded the material before the second variant was added.
        val stale = NewBulkMaterial("Seil", variants = listOf(NewBulkVariant(seil.variants.first().id, availableCount = 4)))

        val message = put("materials/${seil.id}") { setBody<NewMaterial>(stale) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Das Material wurde inzwischen geändert. Lade die Seite neu.", message)
        assertEquals(2, (getMaterial(seil.id) as BulkMaterial).variants.size)
    }

    @Test
    fun `variant names and ids are validated`() = apiTest {
        /** Creates a material, or replaces the one at [path]. */
        suspend fun messageFor(variants: String, path: String? = null, removed: String = "[]"): String {
            val body = """{"trackingType": "INDIVIDUAL", "name": "Seil", "variants": $variants, "removedVariantIds": $removed}"""
            val response = if (path == null) post("materials") { setBody(body) } else put(path) { setBody(body) }
            return response.expectError(HttpStatusCode.BadRequest)
        }

        assertEquals("Ein Material braucht mindestens eine Variante.", messageFor("[]"))
        assertEquals(
            "Hat ein Material mehrere Varianten, braucht jede einen Namen.",
            messageFor("""[{"name": "10 m"}, {}]""")
        )
        assertEquals(
            "Die Varianten eines Materials brauchen unterschiedliche Namen.",
            messageFor("""[{"name": "10 m"}, {"name": "10 m"}]""")
        )
        assertEquals("Der Name einer Variante darf nicht leer sein.", messageFor("""[{"name": " "}]"""))
        assertEquals(
            "Der Name einer Variante darf höchstens 100 Zeichen lang sein.",
            messageFor("""[{"name": "${"x".repeat(101)}"}]""")
        )
        // A single variant may have a name, or none.
        val named = createIndividual("Seil", "10 m")
        assertEquals("10 m", named.variants.single().name)

        // Ids only refer to the material's own existing variants.
        val other = createIndividual("Kohtenblatt")
        assertEquals(
            "Ein neues Material hat noch keine Varianten, die geändert oder entfernt werden können.",
            messageFor("""[{"id": "${other.variantId}"}]""")
        )
        val path = "materials/${named.id}"
        assertEquals(
            "Die Variante ${other.variantId} gehört nicht zu diesem Material.",
            messageFor("""[{"id": "${named.variantId}", "name": "A"}, {"id": "${other.variantId}", "name": "B"}]""", path)
        )
        assertEquals(
            "Die Variante ${other.variantId} gehört nicht zu diesem Material.",
            messageFor("""[{"id": "${named.variantId}"}]""", path, removed = """["${other.variantId}"]""")
        )
        assertEquals(
            "Eine Variante kann nicht zugleich bearbeitet und entfernt werden.",
            messageFor("""[{"id": "${named.variantId}"}]""", path, removed = """["${named.variantId}"]""")
        )
        assertEquals(
            "Eine Variante kann nur einmal vorkommen.",
            messageFor("""[{"id": "${named.variantId}", "name": "A"}, {"id": "${named.variantId}", "name": "B"}]""", path)
        )
        assertEquals(listOf(other.variantId), (getMaterial(other.id) as IndividualMaterial).variants.map { it.id })
    }

    @Test
    fun `variants still in use can't be removed`() = apiTest {
        val seil = createSeil()
        val (tenMeters, fifteenMeters) = seil.variants
        suspend fun removeFifteenMeters() = put("materials/${seil.id}") {
            setBody<NewMaterial>(
                NewBulkMaterial(
                    "Seil",
                    variants = listOf(NewBulkVariant(tenMeters.id, name = "10 m", availableCount = 4)),
                    removedVariantIds = listOf(fifteenMeters.id)
                )
            )
        }

        val purchase: Purchase = post("purchases") { setBody(NewPurchase(fifteenMeters.id, 2)) }.expect(HttpStatusCode.Created)
        assertEquals(
            "Die Variante 15 m hat Einkäufe. Lösche zuerst die Einkäufe.",
            removeFifteenMeters().expectError(HttpStatusCode.Conflict)
        )
        assertEquals(HttpStatusCode.NoContent, delete("purchases/${purchase.id}").status)

        val loan: Loan = post("loans") {
            setBody(NewLoan("Stamm Nord", lentAt, materials = listOf(LoanQuantity(fifteenMeters.id, 1))))
        }.expect(HttpStatusCode.Created)
        assertEquals(
            "Die Variante 15 m wurde schon ausgeliehen und kann nicht entfernt werden.",
            removeFifteenMeters().expectError(HttpStatusCode.Conflict)
        )
        assertEquals(HttpStatusCode.NoContent, delete("loans/${loan.id}").status)

        val group = NewKitGroup(
            amounts = listOf(NewKitGroupAmount(requiredUnits = 1)),
            options = listOf(NewKitGroupOption(seil.id, fifteenMeters.id))
        )
        for (name in listOf("Kohte", "Jurte")) {
            post("kits") { setBody(NewKit(name, groups = listOf(group))) }.expect<Kit>(HttpStatusCode.Created)
        }
        assertEquals(
            "Die Variante 15 m wird in den Sets Jurte, Kohte verwendet. Entferne sie zuerst daraus.",
            removeFifteenMeters().expectError(HttpStatusCode.Conflict)
        )
        // A kit using the material with any variant doesn't keep a variant from being removed.
        for (kit in get("kits").expect<List<Kit>>(HttpStatusCode.OK)) {
            val anyVariant = group.copy(options = listOf(NewKitGroupOption(seil.id)))
            put("kits/${kit.id}") { setBody(NewKit(kit.name, groups = listOf(anyVariant))) }.expect<Kit>(HttpStatusCode.OK)
        }
        val removed = removeFifteenMeters().expect<Material>(HttpStatusCode.OK) as BulkMaterial
        assertEquals(listOf(tenMeters.id), removed.variants.map { it.id })

        // Deleting the material with a variant in a kit is still rejected.
        assertEquals(
            "Das Material wird in einem Set verwendet. Entferne es zuerst aus dem Set.",
            delete("materials/${seil.id}").expectError(HttpStatusCode.Conflict)
        )
    }

    @Test
    fun `variants with items can't be removed`() = apiTest {
        val plane = createKohtenplane()
        val latz = plane.variants.first()
        createItem(plane, "K45 01", variant = latz)
        val removal = NewIndividualMaterial(
            "Kohtenplane",
            variants = listOf(NewIndividualVariant(plane.variants.last().id)),
            removedVariantIds = listOf(latz.id)
        )

        val message = put("materials/${plane.id}") { setBody<NewMaterial>(removal) }.expectError(HttpStatusCode.Conflict)
        assertEquals(
            "Die Variante 45 cm Latz hat noch Gegenstände. Lösche sie zuerst oder ordne sie einer anderen Variante zu.",
            message
        )
    }

    @Test
    fun `stock changes are checked per variant`() = apiTest {
        val seil = createSeil()
        val (tenMeters, fifteenMeters) = seil.variants
        post("purchases") { setBody(NewPurchase(fifteenMeters.id, 2)) }.expect<Purchase>(HttpStatusCode.Created)
        post("loans") { setBody(NewLoan("Stamm Nord", lentAt, materials = listOf(LoanQuantity(tenMeters.id, 3)))) }
            .expect<Loan>(HttpStatusCode.Created)
        fun update(ten: Int, fifteen: Int, expectedFifteen: Int?) = NewBulkMaterial(
            "Seil",
            variants = listOf(
                NewBulkVariant(tenMeters.id, "10 m", availableCount = ten, expectedAvailableCount = 4),
                NewBulkVariant(fifteenMeters.id, "15 m", availableCount = fifteen, expectedAvailableCount = expectedFifteen),
            )
        )

        val stale = put("materials/${seil.id}") { setBody<NewMaterial>(update(4, 6, expectedFifteen = 6)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals(
            "Der Bestand von Seil 15 m wurde inzwischen auf 8 geändert, z. B. durch einen Einkauf. " +
                "Lade die Seite neu und bearbeite das Material erneut.",
            stale
        )
        val tooLow = put("materials/${seil.id}") { setBody<NewMaterial>(update(2, 8, expectedFifteen = 8)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Von Seil 10 m sind 3 ausgeliehen, der Bestand kann nicht kleiner sein.", tooLow)

        val updated = put("materials/${seil.id}") { setBody<NewMaterial>(update(3, 7, expectedFifteen = 8)) }
            .expect<Material>(HttpStatusCode.OK) as BulkMaterial
        assertEquals(listOf(3 to 3, 7 to 0), updated.variants.map { it.availableCount to it.loanedCount })
        assertEquals(10 to 3, updated.availableCount to updated.loanedCount)
    }

    @Test
    fun `identifiers are unique per variant and items can move between variants`() = apiTest {
        val plane = createKohtenplane()
        val (latz, ohne) = plane.variants
        val k01 = createItem(plane, "K01", variant = ohne)
        createItem(plane, "K01", variant = latz)
        createItem(plane, "K02", variant = latz)
        assertEquals(plane.id to latz.id, createItem(plane, "K03", variant = latz).let { it.materialId to it.variantId })

        val taken = post("items") { setBody(NewIndividualItem(latz.id, "K02", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenplane 45 cm Latz K02 gibt es schon.", taken)
        val counts = (getMaterial(plane.id) as IndividualMaterial).variants.map { it.availableCount }
        assertEquals(listOf(3, 1), counts)

        // Moving K01 to the variant with Latz clashes with its K01 there; as K04 it's fine, and the counts follow.
        put("items/${k01.id}") { setBody(NewIndividualItem(latz.id, "K01", ItemCondition.GOOD)) }
            .expectError(HttpStatusCode.Conflict)
        val moved: IndividualItem = put("items/${k01.id}") {
            setBody(NewIndividualItem(latz.id, "K04", ItemCondition.GOOD))
        }.expect(HttpStatusCode.OK)
        assertEquals(latz.id, moved.variantId)
        val material = getMaterial(plane.id) as IndividualMaterial
        assertEquals(listOf(4, 0), material.variants.map { it.availableCount })
        assertEquals(4, material.availableCount)

        // Sorted by variant, then identifier; filtered by material, they include all variants.
        createItem(plane, "K00", variant = ohne)
        val items: List<IndividualItem> = get("items?materialId=${plane.id}").expect(HttpStatusCode.OK)
        assertEquals(listOf("K01", "K02", "K03", "K04", "K00"), items.map { it.identifier })
    }

    @Test
    fun `purchases add to their variant's stock`() = apiTest {
        val seil = createSeil()
        val (tenMeters, fifteenMeters) = seil.variants

        val purchase: Purchase = post("purchases") { setBody(NewPurchase(fifteenMeters.id, 10)) }
            .expect(HttpStatusCode.Created)
        assertEquals(seil.id to fifteenMeters.id, purchase.materialId to purchase.variantId)
        assertEquals(listOf(4, 16), (getMaterial(seil.id) as BulkMaterial).variants.map { it.availableCount })
        assertEquals(listOf(purchase), get("purchases?materialId=${seil.id}").expect<List<Purchase>>(HttpStatusCode.OK))

        val moved = put("purchases/${purchase.id}") { setBody(NewPurchase(tenMeters.id, 10)) }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Die Variante eines Einkaufs kann nicht geändert werden.", moved)

        val variants = listOf(
            NewBulkVariant(tenMeters.id, "10 m", availableCount = 4),
            NewBulkVariant(fifteenMeters.id, "15 m", availableCount = 5)
        )
        put("materials/${seil.id}") { setBody<NewMaterial>(NewBulkMaterial("Seil", variants = variants)) }
            .expect<Material>(HttpStatusCode.OK)
        val message = delete("purchases/${purchase.id}").expectError(HttpStatusCode.Conflict)
        assertEquals("Von Seil 15 m sind 5 im Bestand. Es können nicht 10 abgezogen werden.", message)
    }

    @Test
    fun `loans lend specific variants`() = apiTest {
        val seil = createSeil()
        val (tenMeters, fifteenMeters) = seil.variants
        fun loanOf(vararg quantities: Pair<Uuid, Int>) =
            NewLoan("Stamm Nord", lentAt, materials = quantities.map { (id, n) -> LoanQuantity(id, n) })

        val loan: Loan = post("loans") { setBody(loanOf(tenMeters.id to 4, fifteenMeters.id to 2)) }
            .expect(HttpStatusCode.Created)
        assertEquals(setOf(LoanQuantity(tenMeters.id, 4), LoanQuantity(fifteenMeters.id, 2)), loan.materials.toSet())
        val material = getMaterial(seil.id) as BulkMaterial
        assertEquals(listOf(4, 2), material.variants.map { it.loanedCount })
        assertEquals(6, material.loanedCount)

        val message = post("loans") { setBody(loanOf(tenMeters.id to 1)) }.expectError(HttpStatusCode.Conflict)
        assertEquals("Von Seil 10 m sind nur 0 verfügbar, nicht 1.", message)
        post("loans") { setBody(loanOf(fifteenMeters.id to 4)) }.expect<Loan>(HttpStatusCode.Created)
    }

    @Test
    fun `a loan lends a quantity or specific items per variant`() = apiTest {
        val plane = createKohtenplane()
        val (latz, ohne) = plane.variants
        val k45 = createItem(plane, "K45 01", variant = latz)
        createItem(plane, "K01", variant = ohne)
        createItem(plane, "K02", variant = ohne)

        // Items of one variant and a quantity of another.
        val mixed = NewLoan("Stamm Nord", lentAt, itemIds = listOf(k45.id), materials = listOf(LoanQuantity(ohne.id, 2)))
        val loan: Loan = post("loans") { setBody(mixed) }.expect(HttpStatusCode.Created)
        assertEquals(listOf(1, 2), (getMaterial(plane.id) as IndividualMaterial).variants.map { it.loanedCount })

        val sameVariant = mixed.copy(materials = listOf(LoanQuantity(latz.id, 1)))
        val message = put("loans/${loan.id}") { setBody(sameVariant) }.expectError(HttpStatusCode.BadRequest)
        assertEquals("Gib für Kohtenplane 45 cm Latz entweder eine Anzahl oder bestimmte Gegenstände an.", message)

        val lent = post("loans") { setBody(NewLoan("Stamm Süd", lentAt, itemIds = listOf(k45.id))) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenplane 45 cm Latz K45 01 ist schon ausgeliehen.", lent)
    }

    private suspend fun HttpClient.createSeil() =
        createBulk("Seil", NewBulkVariant(name = "10 m", availableCount = 4), NewBulkVariant(name = "15 m", availableCount = 6))

    /** Its variants are sorted as "45 cm Latz", "ohne Latz". */
    private suspend fun HttpClient.createKohtenplane() = createIndividual("Kohtenplane", "ohne Latz", "45 cm Latz")
}
