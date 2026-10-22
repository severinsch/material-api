import api.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Merging separate materials into variants of one (`POST /materials/merge`). */
class MergeApiTest {

    private val lentAt = LocalDate(2026, 10, 1)

    @Test
    fun `bulk variants move with their stock, purchases and loans`() = apiTest {
        val keller = createLocation("Keller")
        val target = post("materials") {
            setBody<NewMaterial>(
                NewBulkMaterial(
                    "Seil 10 m", category = "Seile", description = "Hanf",
                    variants = listOf(NewBulkVariant(availableCount = 4, storageLocationId = keller.id, locationDetail = "Kiste"))
                )
            )
        }.expect<Material>(HttpStatusCode.Created) as BulkMaterial
        val source = createBulk("Seil 15 m", available = 6)
        val purchase: Purchase = post("purchases") { setBody(NewPurchase(target.variantId, 2)) }.expect(HttpStatusCode.Created)
        val loan: Loan = post("loans") {
            setBody(NewLoan("Stamm Nord", lentAt, materials = listOf(LoanQuantity(source.variantId, 3))))
        }.expect(HttpStatusCode.Created)

        val merged = post("materials/merge") {
            setBody(merge(listOf(target, source), "Seil", target.variantId to "10 m", source.variantId to "15 m"))
        }.expect<Material>(HttpStatusCode.OK) as BulkMaterial

        assertEquals(Triple(target.id, "Seil", "Seile"), Triple(merged.id, merged.name, merged.category))
        assertEquals("Hanf", merged.description)
        assertEquals(
            listOf(
                BulkVariant(target.variantId, "10 m", 6, 0, 0, keller, "Kiste"),
                BulkVariant(source.variantId, "15 m", 6, 0, 3, null, null)
            ),
            merged.variants
        )
        assertEquals(Triple(12, 0, 3), Triple(merged.availableCount, merged.damagedCount, merged.loanedCount))
        assertEquals(merged, getMaterial(target.id))
        assertEquals(HttpStatusCode.NotFound, get("materials/${source.id}").status)
        assertEquals(listOf(merged.id), get("materials").expect<List<Material>>(HttpStatusCode.OK).map { it.id })

        assertEquals(
            listOf(purchase),
            get("purchases?materialId=${target.id}").expect<List<Purchase>>(HttpStatusCode.OK)
        )
        assertEquals(loan, get("loans/${loan.id}").expect<Loan>(HttpStatusCode.OK))
        // The stock is still checked per variant.
        val tooMany = post("loans") {
            setBody(NewLoan("Stamm Süd", lentAt, materials = listOf(LoanQuantity(source.variantId, 4))))
        }.expectError(HttpStatusCode.Conflict)
        assertEquals("Von Seil 15 m sind nur 3 verfügbar, nicht 4.", tooMany)
    }

    @Test
    fun `items move with their variants`() = apiTest {
        val plane = createIndividual("Kohtenplane", "ohne Latz", "45 cm Latz")
        val (latz, ohne) = plane.variants
        createItem(plane, "K01", variant = ohne)
        createItem(plane, "K45 01", variant = latz)
        val alt = createIndividual("Kohtenplane alt")
        val k01 = createItem(alt, "K01")
        createItem(alt, "K02", condition = ItemCondition.POOR)
        val loan: Loan = post("loans") { setBody(NewLoan("Stamm Nord", lentAt, itemIds = listOf(k01.id))) }
            .expect(HttpStatusCode.Created)

        val merged = post("materials/merge") {
            setBody(merge(listOf(plane, alt), "Kohtenplane", alt.variantId to "alt"))
        }.expect<Material>(HttpStatusCode.OK) as IndividualMaterial

        assertEquals(
            listOf(
                IndividualVariant(latz.id, "45 cm Latz", 1, 0, 0),
                IndividualVariant(alt.variantId, "alt", 1, 1, 1),
                IndividualVariant(ohne.id, "ohne Latz", 1, 0, 0)
            ),
            merged.variants
        )
        assertEquals(Triple(3, 1, 1), Triple(merged.availableCount, merged.damagedCount, merged.loanedCount))
        val items: List<IndividualItem> = get("items?materialId=${plane.id}").expect(HttpStatusCode.OK)
        assertEquals(
            listOf("K45 01" to latz.id, "K01" to alt.variantId, "K02" to alt.variantId, "K01" to ohne.id),
            items.map { it.identifier to it.variantId }
        )
        assertEquals(setOf(plane.id), items.map { it.materialId }.toSet())
        assertEquals(loan, get("loans/${loan.id}").expect<Loan>(HttpStatusCode.OK))
        val lent = post("loans") { setBody(NewLoan("Stamm Süd", lentAt, itemIds = listOf(k01.id))) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Kohtenplane alt K01 ist schon ausgeliehen.", lent)
    }

    @Test
    fun `names can be swapped and taken over from merged materials`() = apiTest {
        // The target takes the name of a source, and the variants swap theirs.
        val target = createBulk("Seil", NewBulkVariant(name = "lang", availableCount = 1))
        val source = createBulk("Seil kurz", NewBulkVariant(name = "kurz", availableCount = 2))

        val merged = post("materials/merge") {
            setBody(merge(listOf(target, source), "Seil kurz", target.variantId to "kurz", source.variantId to "lang"))
        }.expect<Material>(HttpStatusCode.OK) as BulkMaterial

        assertEquals("Seil kurz", merged.name)
        assertEquals(listOf("kurz" to 1, "lang" to 2), merged.variants.map { it.name to it.availableCount })

        // Variants keep their current names unless renamed.
        val third = createBulk("Seil mittel", NewBulkVariant(name = "mittel", availableCount = 3))
        val again = post("materials/merge") { setBody(merge(listOf(merged, third), "Seil")) }
            .expect<Material>(HttpStatusCode.OK) as BulkMaterial
        assertEquals(listOf("kurz", "lang", "mittel"), again.variants.map { it.name })
    }

    @Test
    fun `kit options with any variant of a single-variant material get that variant`() = apiTest {
        val ten = createBulk("Seil 10 m", available = 4)
        val fifteen = createBulk("Seil 15 m", available = 6)
        val twenty = createBulk("Seil 20 m", available = 2)
        val hering = createBulk("Hering")
        // Any variant of target and source in one group, a source alone, and a material that isn't merged.
        val jurte = createKit("Jurte", option(ten.id) + option(fifteen.id, unitValue = 2), option(hering.id))
        val kohte = createKit("Kohte", option(twenty.id, priority = 1))

        post("materials/merge") {
            setBody(
                merge(
                    listOf(ten, fifteen, twenty), "Seil",
                    ten.variantId to "10 m", fifteen.variantId to "15 m", twenty.variantId to "20 m"
                )
            )
        }.expect<Material>(HttpStatusCode.OK)

        val kits = get("kits").expect<List<Kit>>(HttpStatusCode.OK).associateBy { it.name }
        assertEquals(
            listOf(
                setOf(
                    KitGroupOption(ten.id, ten.variantId, 1, 0),
                    KitGroupOption(ten.id, fifteen.variantId, 2, 0)
                ),
                setOf(KitGroupOption(hering.id, null, 1, 0))
            ),
            kits.getValue(jurte.name).groups.map { it.options.toSet() }
        )
        assertEquals(
            listOf(KitGroupOption(ten.id, twenty.variantId, 1, 1)),
            kits.getValue(kohte.name).groups.single().options
        )
    }

    @Test
    fun `a target with several variants keeps any variant, and specific variants follow`() = apiTest {
        val seil = createVariants("Seil", "10 m", "15 m")
        val alt = createVariants("Seil alt", "alt 10 m", "alt 15 m")
        val twenty = createBulk("Seil 20 m", available = 2)
        val jurte = createKit("Jurte", option(seil.id))
        // A source with several variants may be merged as long as kits name specific variants of it.
        val kohte = createKit("Kohte", option(alt.id, alt.variants.first().id) + option(twenty.id))

        val merged = post("materials/merge") {
            setBody(merge(listOf(seil, alt, twenty), "Seil", twenty.variantId to "20 m"))
        }.expect<Material>(HttpStatusCode.OK) as BulkMaterial

        assertEquals(listOf("10 m", "15 m", "20 m", "alt 10 m", "alt 15 m"), merged.variants.map { it.name })
        val kits = get("kits").expect<List<Kit>>(HttpStatusCode.OK).associateBy { it.id }
        assertEquals(listOf(KitGroupOption(seil.id, null, 1, 0)), kits.getValue(jurte.id).groups.single().options)
        assertEquals(
            setOf(KitGroupOption(seil.id, alt.variants.first().id, 1, 0), KitGroupOption(seil.id, twenty.variantId, 1, 0)),
            kits.getValue(kohte.id).groups.single().options.toSet()
        )
    }

    @Test
    fun `merges that would change what kits mean are rejected`() = apiTest {
        val seil = createVariants("Seil", "10 m", "15 m")
        val kanister = createVariants("Kanister", "10 L", "20 L")
        val twenty = createBulk("Seil 20 m", available = 2)
        createKit("Kohte", option(kanister.id))
        createKit("Jurte", option(kanister.id))

        // Any variant of a source with several variants.
        val anyOfSource = post("materials/merge") { setBody(merge(listOf(seil, kanister), "Seil")) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals(
            "Kanister wird in den Sets Jurte, Kohte mit beliebiger Variante verwendet, was sich nach dem Zusammenführen " +
                "nicht mehr ausdrücken lässt. Wähle dort zuerst bestimmte Varianten aus, oder führe die anderen " +
                "Materialien in dieses zusammen.",
            anyOfSource
        )

        // Any variant of the target together with a source in one group.
        createKit("Wasser", option(seil.id) + option(twenty.id))
        val overlap = post("materials/merge") { setBody(merge(listOf(seil, twenty), "Seil", twenty.variantId to "20 m")) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals(
            "Seil wird im Set Wasser mit beliebiger Variante verwendet, zusammen mit einem der anderen Materialien im " +
                "selben Teil. Danach würden sich die Materialien des Teils überschneiden. Entferne zuerst eines davon " +
                "aus dem Teil.",
            overlap
        )

        // Nothing changed.
        assertEquals(seil, getMaterial(seil.id))
        assertEquals(kanister, getMaterial(kanister.id))
        assertEquals(twenty, getMaterial(twenty.id))
    }

    @Test
    fun `merges are validated`() = apiTest { context ->
        val seil = createVariants("Seil", "10 m", "15 m")
        val twenty = createBulk("Seil 20 m", available = 2)
        val hering = createBulk("Hering")
        val blatt = createIndividual("Kohtenblatt")
        val foreign = context.clientFor(TenantRole.EDITOR, TestDatabase.createTenant("Stamm West")).createBulk("Seil 30 m")
        suspend fun messageFor(body: String) = post("materials/merge") { setBody(body) }.expectError(HttpStatusCode.BadRequest)
        fun body(vararg ids: Uuid, name: String = "Seil", variantNames: String = "{}") =
            """{"materialIds": [${ids.joinToString { "\"$it\"" }}], "name": "$name", "variantNames": $variantNames}"""

        assertEquals("Wähle mindestens zwei Materialien zum Zusammenführen aus.", messageFor(body(seil.id)))
        assertEquals("Ein Material kann nur einmal vorkommen.", messageFor(body(seil.id, twenty.id, seil.id)))
        assertEquals("Der Name darf nicht leer sein.", messageFor(body(seil.id, twenty.id, name = " ")))
        assertEquals(
            "Der Name einer Variante darf nicht leer sein.",
            messageFor(body(seil.id, twenty.id, variantNames = """{"${twenty.variantId}": ""}"""))
        )
        // The client only lists materials and variants it has seen, so unknown ones were deleted or merged meanwhile.
        // Another tenant's material doesn't exist here.
        suspend fun conflictFor(body: String) = post("materials/merge") { setBody(body) }.expectError(HttpStatusCode.Conflict)
        val changed = "Die Materialien wurden inzwischen geändert. Lade die Seite neu."
        assertEquals(changed, conflictFor(body(seil.id, Uuid.random())))
        assertEquals(changed, conflictFor(body(seil.id, foreign.id)))
        assertEquals(changed, conflictFor(body(seil.id, twenty.id, variantNames = """{"${Uuid.random()}": "x"}""")))
        assertEquals(
            "Nur Materialien derselben Erfassungsart können zusammengeführt werden.",
            messageFor(body(seil.id, blatt.id))
        )
        assertEquals(
            "Die Variante ${hering.variantId} gehört zu keinem der zusammengeführten Materialien.",
            messageFor(body(seil.id, twenty.id, variantNames = """{"${twenty.variantId}": "20 m", "${hering.variantId}": "x"}"""))
        )
        assertEquals(
            "Hat ein Material mehrere Varianten, braucht jede einen Namen.",
            messageFor(body(seil.id, twenty.id))
        )
        assertEquals(
            "Die Varianten eines Materials brauchen unterschiedliche Namen.",
            messageFor(body(seil.id, twenty.id, variantNames = """{"${twenty.variantId}": "15 m"}"""))
        )
        val twentyNamed = """{"${twenty.variantId}": "20 m"}"""
        val taken = post("materials/merge") { setBody(body(seil.id, twenty.id, name = "Hering", variantNames = twentyNamed)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Es gibt schon ein Material mit diesem Namen.", taken)

        assertEquals(listOf(hering, blatt, seil, twenty), get("materials").expect<List<Material>>(HttpStatusCode.OK))
    }

    /** Merges [materials] into the first one. */
    private fun merge(materials: List<Material>, name: String, vararg variantNames: Pair<Uuid, String>) =
        MaterialMerge(materials.map { it.id }, name, variantNames.toMap())

    private fun option(materialId: Uuid, variantId: Uuid? = null, unitValue: Int = 1, priority: Int = 0) =
        listOf(NewKitGroupOption(materialId, variantId, unitValue, priority))

    /** A bulk material with a variant of one piece per name. */
    private suspend fun HttpClient.createVariants(name: String, vararg variantNames: String): BulkMaterial =
        createBulk(name, *variantNames.map { NewBulkVariant(name = it, availableCount = 1) }.toTypedArray())

    /** A kit with a group of one unit per list of options. */
    private suspend fun HttpClient.createKit(name: String, vararg groups: List<NewKitGroupOption>): Kit {
        val newGroups = groups.map { NewKitGroup(amounts = listOf(NewKitGroupAmount(requiredUnits = 1)), options = it) }
        return post("kits") { setBody(NewKit(name, groups = newGroups)) }.expect(HttpStatusCode.Created)
    }
}
