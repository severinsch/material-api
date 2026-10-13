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
// the column sizes in db/schema.sql.

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
        requireText(name, "name", maxLength = 100)
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
    val locationDetail: String? = null
) : NewMaterial {
    init {
        requireMaterialFields()
        requireNonNegative(availableCount, "availableCount")
        requireNonNegative(damagedCount, "damagedCount")
        requireMaxLength(locationDetail, "locationDetail", 255)
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
    requireText(name, "name", maxLength = 255)
    requireMaxLength(category, "category", 100)
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
    val onLoan: Boolean
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
        requireText(identifier, "identifier", maxLength = 100)
        requireMaxLength(locationDetail, "locationDetail", 255)
        requirePrice(purchasePrice, "purchasePrice")
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

/** [materialId] can't be changed after creation. */
@Serializable
data class NewPurchase(
    val materialId: Uuid,
    val quantity: Int,
    val unitPrice: Double? = null,
    val purchaseDate: LocalDate? = null,
    val note: String? = null
) {
    init {
        require(quantity > 0) { "quantity must be positive" }
        requirePrice(unitPrice, "unitPrice")
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
 */
@Serializable
data class NewDamageReport(
    val itemId: Uuid,
    val description: String,
    val reportedBy: String? = null,
    val isResolved: Boolean = false,
    val resolutionNotes: String? = null
) {
    init {
        requireText(description, "description")
        requireMaxLength(reportedBy, "reportedBy", 255)
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
        requireText(name, "name", maxLength = 255)
        val names = groups.mapNotNull { it.name }
        require(names.size == names.toSet().size) { "group names must be unique within a kit" }
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
        name?.let { requireText(it, "group name", maxLength = 255) }
        require(requiredUnits > 0) { "requiredUnits must be positive" }
        require(options.isNotEmpty()) { "a group needs at least one option" }
        require(options.distinctBy { it.materialId }.size == options.size) { "a material can only appear once per group" }
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
        require(unitValue > 0) { "unitValue must be positive" }
    }
}

// ========= Loans =========

/** A loan is open until [returnedAt] is set. */
@Serializable
data class Loan(
    val id: Uuid,
    val borrower: String,
    val lentAt: LocalDate,
    val dueAt: LocalDate?,
    val returnedAt: LocalDate?,
    val note: String?,
    val itemIds: List<Uuid>,
    val bulkMaterials: List<BulkLoanLine>
)

/** Only active items can be lent, and an item can only be on one open loan at a time. */
@Serializable
data class NewLoan(
    val borrower: String,
    val lentAt: LocalDate,
    val dueAt: LocalDate? = null,
    val returnedAt: LocalDate? = null,
    val note: String? = null,
    val itemIds: List<Uuid> = emptyList(),
    val bulkMaterials: List<BulkLoanLine> = emptyList()
) {
    init {
        requireText(borrower, "borrower", maxLength = 255)
        require(itemIds.isNotEmpty() || bulkMaterials.isNotEmpty()) { "a loan needs at least one item or material" }
        require(itemIds.toSet().size == itemIds.size) { "an item can only appear once per loan" }
        require(bulkMaterials.distinctBy { it.materialId }.size == bulkMaterials.size) {
            "a material can only appear once per loan"
        }
        dueAt?.let { require(it >= lentAt) { "dueAt must not be before lentAt" } }
        returnedAt?.let { require(it >= lentAt) { "returnedAt must not be before lentAt" } }
    }
}

@Serializable
data class BulkLoanLine(
    val materialId: Uuid,
    val quantity: Int
) {
    init {
        require(quantity > 0) { "quantity must be positive" }
    }
}

// ========= Validation helpers =========

private const val MAX_PRICE = 99_999_999.99 // DECIMAL(10, 2)

private fun requireText(value: String, field: String, maxLength: Int? = null) {
    require(value.isNotBlank()) { "$field must not be blank" }
    maxLength?.let { requireMaxLength(value, field, it) }
}

private fun requireMaxLength(value: String?, field: String, maxLength: Int) =
    require(value == null || value.length <= maxLength) { "$field must be at most $maxLength characters long" }

private fun requireNonNegative(value: Int, field: String) = require(value >= 0) { "$field must not be negative" }

private fun requirePrice(value: Double?, field: String) {
    if (value == null) return
    require(value in 0.0..MAX_PRICE) { "$field must be between 0 and 99999999.99" }
    require(value.toBigDecimal().stripTrailingZeros().scale() <= 2) { "$field must not have more than 2 decimals" }
}
