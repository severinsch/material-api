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
 * Shared properties of both material types. Loaned pieces are still included in [availableCount]; [loanedCount] is
 * the part of it that is currently lent out, so clients subtract it if they want what is currently in storage.
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
    val location: StorageLocation?,
    val locationDetail: String?
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
    override val loanedCount: Int
) : Material

/** The type of a material can't be changed after creation. */
@Serializable
@JsonClassDiscriminator("trackingType")
sealed interface NewMaterial {
    val name: String
    val category: String?
    val description: String?
}

@Serializable
@SerialName("BULK")
data class NewBulkMaterial(
    override val name: String,
    override val category: String? = null,
    override val description: String? = null,
    val availableCount: Int,
    val damagedCount: Int = 0,
    val storageLocationId: Uuid? = null,
    val locationDetail: String? = null,
    /**
     * On update: the available count the client started from. The update is rejected with 409 if the stock changed
     * since, e.g. by a purchase, so it isn't overwritten unnoticed.
     */
    val expectedAvailableCount: Int? = null
) : NewMaterial {
    init {
        requireMaterialFields()
        requireNonNegative(availableCount, "Der verfügbare Bestand")
        requireNonNegative(damagedCount, "Der beschädigte Bestand")
        requireMaxLength(locationDetail, "Der Platz im Lagerort", 255)
    }
}

@Serializable
@SerialName("INDIVIDUAL")
data class NewIndividualMaterial(
    override val name: String,
    override val category: String? = null,
    override val description: String? = null
) : NewMaterial {
    init {
        requireMaterialFields()
    }
}

private fun NewMaterial.requireMaterialFields() {
    requireText(name, "Der Name", maxLength = 255)
    requireMaxLength(category, "Die Kategorie", 100)
}

// ========= Individual items =========

@Serializable
data class IndividualItem(
    val id: Uuid,
    val materialId: Uuid,
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

/** [materialId] can't be changed after creation. */
@Serializable
data class NewIndividualItem(
    val materialId: Uuid,
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
    val materialId: Uuid,
    val quantity: Int,
    val unitPrice: Double?,
    val purchaseDate: LocalDate?,
    val note: String?
)

/**
 * Recording a purchase adds its [quantity] to the material's available stock; changing or deleting it adjusts the
 * stock accordingly. [materialId] can't be changed after creation.
 */
@Serializable
data class NewPurchase(
    val materialId: Uuid,
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
 * A slot in a kit that needs [requiredUnits] units, filled by one or more interchangeable materials.
 * A fixed material ("8x Fenster") is a group with a single option of unit value 1 and no name.
 * Optional groups are all-or-nothing: packed completely or not at all.
 */
@Serializable
data class KitGroup(
    val name: String?,
    val requiredUnits: Int,
    val isOptional: Boolean,
    val options: List<KitGroupOption>
)

@Serializable
data class NewKitGroup(
    val name: String? = null,
    val requiredUnits: Int,
    val isOptional: Boolean = false,
    val options: List<NewKitGroupOption>
) {
    init {
        name?.let { requireText(it, "Der Name eines Teils", maxLength = 255) }
        requirePositive(requiredUnits, "Die Anzahl der Einheiten")
        require(options.isNotEmpty()) { "Jedes Teil braucht mindestens ein Material." }
        require(options.distinctBy { it.materialId }.size == options.size) {
            "Ein Material kann in einem Teil nur einmal vorkommen."
        }
    }
}

/** How much one piece of a material contributes to a group. Options with lower [priority] are used first. */
@Serializable
data class KitGroupOption(
    val materialId: Uuid,
    val unitValue: Int,
    val priority: Int
)

@Serializable
data class NewKitGroupOption(
    val materialId: Uuid,
    val unitValue: Int = 1,
    val priority: Int = 0
) {
    init {
        requirePositive(unitValue, "Die Einheiten pro Stück")
    }
}

// ========= Loans =========

/**
 * A loan is open until [returnedAt] is set. It lends quantities of materials without saying which pieces
 * ([materials], e.g. "4 Kohtenblätter, 30 Heringe") and specific items ([itemIds], e.g. "Jurte 3"), but not both for
 * the same material.
 */
@Serializable
data class Loan(
    val id: Uuid,
    val borrower: String,
    val lentAt: LocalDate,
    val dueAt: LocalDate?,
    val returnedAt: LocalDate?,
    val note: String?,
    val itemIds: List<Uuid>,
    val materials: List<LoanQuantity>
)

/**
 * Specific items must be active and on no other open loan; quantities must not exceed what is available, i.e. the
 * stock or the available items minus what other open loans hold.
 */
@Serializable
data class NewLoan(
    val borrower: String,
    val lentAt: LocalDate,
    val dueAt: LocalDate? = null,
    val returnedAt: LocalDate? = null,
    val note: String? = null,
    val itemIds: List<Uuid> = emptyList(),
    val materials: List<LoanQuantity> = emptyList()
) {
    init {
        requireText(borrower, "„Ausgeliehen an“", maxLength = 255)
        require(itemIds.isNotEmpty() || materials.isNotEmpty()) { "Wähle mindestens einen Gegenstand oder ein Material aus." }
        require(itemIds.toSet().size == itemIds.size) { "Ein Gegenstand kann in einer Ausleihe nur einmal vorkommen." }
        require(materials.distinctBy { it.materialId }.size == materials.size) {
            "Ein Material kann in einer Ausleihe nur einmal vorkommen."
        }
        dueAt?.let { require(it >= lentAt) { "Das Rückgabedatum kann nicht vor dem Ausleihdatum liegen." } }
        returnedAt?.let { require(it >= lentAt) { "Das Datum der Rückgabe kann nicht vor dem Ausleihdatum liegen." } }
    }
}

/** A quantity of a material, without saying which pieces. */
@Serializable
data class LoanQuantity(
    val materialId: Uuid,
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
