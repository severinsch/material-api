@file:OptIn(ExperimentalSerializationApi::class)

package api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Request and response bodies. `New*` classes are used for both creating (POST) and replacing (PUT) a resource.
// Validation happens in init blocks, so invalid bodies are rejected with 400 while being received. The limits match
// the column sizes in the migrations. Messages are shown to users of the (German) frontend, so they are in German and
// name fields as the frontend labels them.

@Serializable
data class ErrorResponse(val message: String)

@Serializable
@SerialName("MaterialTrackingType")
enum class MaterialTrackingType { BULK, INDIVIDUAL }

@Serializable
@SerialName("ItemCondition")
enum class ItemCondition { NEW, GOOD, FAIR, POOR }

@Serializable
@SerialName("ItemStatus")
enum class ItemStatus { ACTIVE, IN_REPAIR, LOST, DECOMMISSIONED }

// ========= Locations =========

@Serializable
data class StorageLocation(
    val id: Uuid,
    val name: String,
    val description: String?
)

@Serializable
data class NewStorageLocation(
    val name: String,
    val description: String? = null
) {
    init {
        requireText(name, "Der Name", maxLength = 100)
    }
}

// ========= Materials =========

/**
 * Shared properties of both material types. The counts are sums over the material's variants. Loaned pieces are
 * still included in [availableCount]; [loanedCount] is the part of it that is currently lent out, so clients subtract
 * it if they want what is currently in storage.
 */
@Serializable
@JsonClassDiscriminator("trackingType")
sealed interface Material {
    val id: Uuid
    val name: String
    val category: String?
    val description: String?
    val availableCount: Int
    val damagedCount: Int
    val loanedCount: Int
}

@Serializable
@SerialName("BULK")
data class BulkMaterial(
    override val id: Uuid,
    override val name: String,
    override val category: String?,
    override val description: String?,
    override val availableCount: Int,
    override val damagedCount: Int,
    override val loanedCount: Int,
    /** At least one, sorted by name (numbers by value: 5 m, 10 m, 15 m). */
    val variants: List<BulkVariant>
) : Material

/** Counts are derived from the material's items: available = active and not POOR, damaged = POOR or in repair. */
@Serializable
@SerialName("INDIVIDUAL")
data class IndividualMaterial(
    override val id: Uuid,
    override val name: String,
    override val category: String?,
    override val description: String?,
    override val availableCount: Int,
    override val damagedCount: Int,
    override val loanedCount: Int,
    /** At least one, sorted by name (numbers by value: 5 m, 10 m, 15 m). */
    val variants: List<IndividualVariant>
) : Material

// Variants hold what differs between otherwise equal materials, e.g. the lengths of a rope. Every material has at
// least one; a material with a single variant usually leaves it unnamed (name null), and clients don't show variants
// for it. The counts mean the same as those of the material. Plain classes, so the variant lists are concretely typed.

/** A variant of a bulk material with its own stock and location. */
@Serializable
data class BulkVariant(
    val id: Uuid,
    val name: String?,
    val availableCount: Int,
    val damagedCount: Int,
    val loanedCount: Int,
    val location: StorageLocation?,
    val locationDetail: String?
)

/** A variant of an individual material; its items reference it. */
@Serializable
data class IndividualVariant(
    val id: Uuid,
    val name: String?,
    val availableCount: Int,
    val damagedCount: Int,
    val loanedCount: Int
)

/**
 * The type of a material can't be changed after creation.
 *
 * The variants are the complete list: on update, entries with an `id` change that variant, entries without one are
 * created, and the variants in [removedVariantIds] are deleted (only while nothing references them). An existing
 * variant that is neither listed nor removed, e.g. one another user just added, is a conflict (409).
 */
@Serializable
@JsonClassDiscriminator("trackingType")
sealed interface NewMaterial {
    val name: String
    val category: String?
    val description: String?
    val removedVariantIds: List<Uuid>
}

@Serializable
@SerialName("BULK")
data class NewBulkMaterial(
    override val name: String,
    override val category: String? = null,
    override val description: String? = null,
    val variants: List<NewBulkVariant>,
    override val removedVariantIds: List<Uuid> = emptyList()
) : NewMaterial {
    init {
        requireMaterialFields()
        requireVariants(variants.map { it.id to it.name })
    }
}

@Serializable
@SerialName("INDIVIDUAL")
data class NewIndividualMaterial(
    override val name: String,
    override val category: String? = null,
    override val description: String? = null,
    val variants: List<NewIndividualVariant> = listOf(NewIndividualVariant()),
    override val removedVariantIds: List<Uuid> = emptyList()
) : NewMaterial {
    init {
        requireMaterialFields()
        requireVariants(variants.map { it.id to it.name })
    }
}

/** [id] is null for a new variant. */
@Serializable
data class NewBulkVariant(
    val id: Uuid? = null,
    val name: String? = null,
    val availableCount: Int,
    val damagedCount: Int = 0,
    val storageLocationId: Uuid? = null,
    val locationDetail: String? = null,
    /**
     * On update: the available count the client started from. The update is rejected with 409 if the stock changed
     * since, e.g. by a purchase, so it isn't overwritten unnoticed.
     */
    val expectedAvailableCount: Int? = null
) {
    init {
        requireNonNegative(availableCount, "Der verfügbare Bestand")
        requireNonNegative(damagedCount, "Der beschädigte Bestand")
        requireMaxLength(locationDetail, "Der Platz im Lagerort", 255)
    }
}

/** [id] is null for a new variant. */
@Serializable
data class NewIndividualVariant(
    val id: Uuid? = null,
    val name: String? = null
)

/**
 * Merges materials of the same type into one, e.g. "Seil 10 m", "Seil 15 m" and "Seil 20 m" into "Seil" with three
 * variants. The first of [materialIds] is the target: it keeps its id, category and description and gets [name]. The
 * variants of the others move to it with their items, stock, purchases and loans, and the others are deleted.
 *
 * Afterwards every variant needs a name, unique within the material: the one in [variantNames] (by variant id) or its
 * current one. Kit options keep their meaning: "any variant" of a material with a single variant becomes that
 * variant. Other materials with several variants must not be used with "any variant" in kits, since that can't be
 * expressed after the merge.
 */
@Serializable
data class MaterialMerge(
    val materialIds: List<Uuid>,
    val name: String,
    val variantNames: Map<Uuid, String> = emptyMap()
) {
    init {
        require(materialIds.size >= 2) { "Wähle mindestens zwei Materialien zum Zusammenführen aus." }
        require(materialIds.toSet().size == materialIds.size) { "Ein Material kann nur einmal vorkommen." }
        requireText(name, "Der Name", maxLength = 255)
        variantNames.values.forEach { requireText(it, "Der Name einer Variante", maxLength = 100) }
    }
}

private fun NewMaterial.requireMaterialFields() {
    requireText(name, "Der Name", maxLength = 255)
    requireMaxLength(category, "Die Kategorie", 100)
}

/** [variants] as pairs of id and name. */
private fun NewMaterial.requireVariants(variants: List<Pair<Uuid?, String?>>) {
    require(variants.isNotEmpty()) { "Ein Material braucht mindestens eine Variante." }
    variants.forEach { (_, name) -> name?.let { requireText(it, "Der Name einer Variante", maxLength = 100) } }
    if (variants.size > 1) {
        require(variants.all { it.second != null }) { "Hat ein Material mehrere Varianten, braucht jede einen Namen." }
        require(variants.distinctBy { it.second }.size == variants.size) {
            "Die Varianten eines Materials brauchen unterschiedliche Namen."
        }
    }
    val ids = variants.mapNotNull { it.first }
    require(ids.toSet().size == ids.size) { "Eine Variante kann nur einmal vorkommen." }
    require(ids.none { it in removedVariantIds }) { "Eine Variante kann nicht zugleich bearbeitet und entfernt werden." }
}

// ========= Individual items =========

@Serializable
data class IndividualItem(
    val id: Uuid,
    /** The material of [variantId], for grouping. */
    val materialId: Uuid,
    val variantId: Uuid,
    val identifier: String,
    val condition: ItemCondition,
    val status: ItemStatus,
    val storageLocation: StorageLocation?,
    val locationDetail: String?,
    val purchasePrice: Double?,
    val purchaseDate: LocalDate?,
    /** Whether the item is part of a loan that hasn't been returned yet. */
    val onLoan: Boolean,
    /** Whether the item has damage reports or loans. Such items can't be deleted, only retired via their status. */
    val hasHistory: Boolean
)

/**
 * Identifiers are unique per variant. On update, [variantId] can only change to another variant of the same
 * material, e.g. to fix a wrong entry.
 */
@Serializable
data class NewIndividualItem(
    val variantId: Uuid,
    val identifier: String,
    val condition: ItemCondition,
    val status: ItemStatus = ItemStatus.ACTIVE,
    val storageLocationId: Uuid? = null,
    val locationDetail: String? = null,
    val purchasePrice: Double? = null,
    val purchaseDate: LocalDate? = null
) {
    init {
        requireText(identifier, "Die Kennung", maxLength = 100)
        requireMaxLength(locationDetail, "Der Platz im Lagerort", 255)
        requirePrice(purchasePrice, "Der Kaufpreis")
    }
}

// ========= Purchases (bulk materials only) =========

@Serializable
data class Purchase(
    val id: Uuid,
    /** The material of [variantId], for grouping. */
    val materialId: Uuid,
    val variantId: Uuid,
    val quantity: Int,
    val unitPrice: Double?,
    val purchaseDate: LocalDate?,
    val note: String?
)

/**
 * Recording a purchase adds its [quantity] to the variant's available stock; changing or deleting it adjusts the
 * stock accordingly. [variantId] can't be changed after creation.
 */
@Serializable
data class NewPurchase(
    val variantId: Uuid,
    val quantity: Int,
    val unitPrice: Double? = null,
    val purchaseDate: LocalDate? = null,
    val note: String? = null
) {
    init {
        requirePositive(quantity, "Die Menge")
        requirePrice(unitPrice, "Der Stückpreis")
    }
}

// ========= Damage reports =========

/** A damage report is resolved iff [resolvedAt] is set. */
@Serializable
data class DamageReport(
    val id: Uuid,
    val itemId: Uuid,
    val description: String,
    val reportedBy: String?,
    val reportedAt: Instant,
    val resolvedAt: Instant?,
    val resolutionNotes: String?
)

/**
 * Setting [isResolved] records the current time as resolution time; an existing resolution time is kept.
 * [itemId] can't be changed after creation.
 *
 * Reports are informational and don't change the item by themselves. [itemStatus] optionally sets the item's status
 * in the same request, e.g. to IN_REPAIR when reporting damage or back to ACTIVE when resolving it.
 */
@Serializable
data class NewDamageReport(
    val itemId: Uuid,
    val description: String,
    val reportedBy: String? = null,
    val isResolved: Boolean = false,
    val resolutionNotes: String? = null,
    val itemStatus: ItemStatus? = null
) {
    init {
        requireText(description, "Die Beschreibung")
        requireMaxLength(reportedBy, "„Gemeldet von“", 255)
    }
}

// ========= Kits =========

@Serializable
data class Kit(
    val id: Uuid,
    val name: String,
    val description: String?,
    /** In display order. */
    val groups: List<KitGroup>
)

@Serializable
data class NewKit(
    val name: String,
    val description: String? = null,
    val groups: List<NewKitGroup>
) {
    init {
        requireText(name, "Der Name", maxLength = 255)
        require(groups.isNotEmpty()) { "Ein Set braucht mindestens ein Teil." }
        val names = groups.mapNotNull { it.name }
        require(names.size == names.toSet().size) { "Die Teile eines Sets brauchen unterschiedliche Namen." }
    }
}

/**
 * A slot in a kit that needs a number of units, filled by one or more interchangeable materials.
 * A fixed material ("8x Fenster") is a group with a single amount, a single option of unit value 1 and no name.
 */
@Serializable
data class KitGroup(
    val name: String?,
    /** In display order; the packing list uses one of them per kit, the first one by default. */
    val amounts: List<KitGroupAmount>,
    val options: List<KitGroupOption>
)

/**
 * The number of units a group can be packed with ("Ausführung"), e.g. Einbein = 1 and Dreibein = 3 Jurtenstangen.
 * An optional group has an amount of 0 ("ohne" = 0, "mit" = 12 Fensterplanen). [label] is null only for a group's
 * single amount.
 */
@Serializable
data class KitGroupAmount(
    val label: String?,
    val requiredUnits: Int
)

@Serializable
data class NewKitGroup(
    val name: String? = null,
    val amounts: List<NewKitGroupAmount>,
    val options: List<NewKitGroupOption>
) {
    init {
        name?.let { requireText(it, "Der Name eines Teils", maxLength = 255) }
        require(amounts.isNotEmpty()) { "Jedes Teil braucht mindestens eine Ausführung." }
        require(amounts.any { it.requiredUnits > 0 }) { "Mindestens eine Ausführung eines Teils braucht Einheiten." }
        if (amounts.size > 1) {
            require(amounts.all { it.label != null }) {
                "Hat ein Teil mehrere Ausführungen, braucht jede eine Bezeichnung."
            }
            require(amounts.distinctBy { it.label }.size == amounts.size) {
                "Die Ausführungen eines Teils brauchen unterschiedliche Bezeichnungen."
            }
        }
        require(options.isNotEmpty()) { "Jedes Teil braucht mindestens ein Material." }
        // "Any variant" covers the specific ones, so a material is either one option or one per distinct variant.
        val overlapping = options.groupBy { it.materialId }.values.any { same ->
            same.size > 1 && (same.any { it.variantId == null } || same.distinctBy { it.variantId }.size < same.size)
        }
        require(!overlapping) {
            "Die Materialien eines Teils überschneiden sich: Nimm ein Material entweder einmal mit beliebiger " +
                "Variante auf oder mit unterschiedlichen, bestimmten Varianten."
        }
    }
}

@Serializable
data class NewKitGroupAmount(
    val label: String? = null,
    val requiredUnits: Int
) {
    init {
        label?.let { requireText(it, "Die Bezeichnung einer Ausführung", maxLength = 100) }
        requireNonNegative(requiredUnits, "Die Anzahl der Einheiten")
    }
}

/**
 * How much one piece of a material contributes to a group. Options with lower [priority] are used first.
 * [variantId] null means any variant of the material, including variants added later, all with the same
 * [unitValue]; otherwise only that variant of the material counts.
 */
@Serializable
data class KitGroupOption(
    val materialId: Uuid,
    val variantId: Uuid?,
    val unitValue: Int,
    val priority: Int
)

/** [variantId], if given, must be a variant of [materialId]. */
@Serializable
data class NewKitGroupOption(
    val materialId: Uuid,
    val variantId: Uuid? = null,
    val unitValue: Int = 1,
    val priority: Int = 0
) {
    init {
        requirePositive(unitValue, "Die Einheiten pro Stück")
    }
}

// ========= Loans =========

/**
 * A loan is open until [returnedAt] is set. It lends quantities of material variants without saying which pieces
 * ([materials], e.g. "4 Kohtenblätter, 3 Seile 15 m") and specific items ([itemIds], e.g. "Jurte 3"), but not both for
 * the same variant.
 */
@Serializable
data class Loan(
    val id: Uuid,
    /** The borrower's name; for members their current display name. */
    val borrower: String,
    /** The borrowing member, or null for someone without an account (or whose account was deleted). */
    val borrowerId: Uuid?,
    val lentAt: LocalDate,
    val dueAt: LocalDate?,
    val returnedAt: LocalDate?,
    val note: String?,
    val itemIds: List<Uuid>,
    val materials: List<LoanQuantity>
)

/**
 * The borrower is either a member of the tenant ([borrowerId]), or someone without an account given by name
 * ([borrower], ignored for members). Specific items must be active and on no other open loan; quantities must not
 * exceed what is available, i.e. the stock or the available items minus what other open loans hold.
 */
@Serializable
data class NewLoan(
    val borrower: String? = null,
    val lentAt: LocalDate,
    val dueAt: LocalDate? = null,
    val returnedAt: LocalDate? = null,
    val note: String? = null,
    val itemIds: List<Uuid> = emptyList(),
    val materials: List<LoanQuantity> = emptyList(),
    val borrowerId: Uuid? = null
) {
    init {
        if (borrowerId == null) requireText(borrower.orEmpty(), "„Ausgeliehen an“", maxLength = 255)
        require(itemIds.isNotEmpty() || materials.isNotEmpty()) { "Wähle mindestens einen Gegenstand oder ein Material aus." }
        require(itemIds.toSet().size == itemIds.size) { "Ein Gegenstand kann in einer Ausleihe nur einmal vorkommen." }
        require(materials.distinctBy { it.variantId }.size == materials.size) {
            "Eine Variante kann in einer Ausleihe nur einmal vorkommen."
        }
        dueAt?.let { require(it >= lentAt) { "Das Rückgabedatum kann nicht vor dem Ausleihdatum liegen." } }
        returnedAt?.let { require(it >= lentAt) { "Das Datum der Rückgabe kann nicht vor dem Ausleihdatum liegen." } }
    }
}

/** A quantity of a material variant, without saying which pieces. */
@Serializable
data class LoanQuantity(
    val variantId: Uuid,
    val quantity: Int
) {
    init {
        requirePositive(quantity, "Die Menge")
    }
}

// ========= Validation helpers =========

private const val MAX_PRICE = 99_999_999.99 // DECIMAL(10, 2)

// [field] is the subject of the message including its article, e.g. "Der Name".

internal fun requireText(value: String, field: String, maxLength: Int? = null) {
    require(value.isNotBlank()) { "$field darf nicht leer sein." }
    maxLength?.let { requireMaxLength(value, field, it) }
}

internal fun requireMaxLength(value: String?, field: String, maxLength: Int) =
    require(value == null || value.length <= maxLength) { "$field darf höchstens $maxLength Zeichen lang sein." }

private fun requireNonNegative(value: Int, field: String) = require(value >= 0) { "$field darf nicht negativ sein." }

private fun requirePositive(value: Int, field: String) = require(value > 0) { "$field muss mindestens 1 sein." }

private fun requirePrice(value: Double?, field: String) {
    if (value == null) return
    require(value in 0.0..MAX_PRICE) { "$field muss zwischen 0 und 99.999.999,99 € liegen." }
    require(value.toBigDecimal().stripTrailingZeros().scale() <= 2) { "$field darf höchstens 2 Nachkommastellen haben." }
}
