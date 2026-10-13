@file:OptIn(ExperimentalSerializationApi::class)

package api

import database.ItemCondition
import database.ItemStatus
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Request and response bodies. `New*` classes are used for both creating (POST) and replacing (PUT) a resource.
// Validation happens in init blocks, so invalid bodies are rejected with 400 while being received.

@Serializable
data class ErrorResponse(val message: String)

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
        requireNotBlank(name, "name")
    }
}

// ========= Materials =========

/**
 * Shared properties of both material types. Loaned pieces are still included in [availableCount] / [damagedCount];
 * clients subtract [loanedCount] if they want what is currently in storage.
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

@Serializable
data class NewBulkMaterial(
    val name: String,
    val category: String? = null,
    val description: String? = null,
    val availableCount: Int,
    val damagedCount: Int = 0,
    val storageLocationId: Uuid? = null,
    val locationDetail: String? = null
) {
    init {
        requireNotBlank(name, "name")
        requireNonNegative(availableCount, "availableCount")
        requireNonNegative(damagedCount, "damagedCount")
    }
}

@Serializable
data class NewIndividualMaterial(
    val name: String,
    val category: String? = null,
    val description: String? = null
) {
    init {
        requireNotBlank(name, "name")
    }
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
        requireNotBlank(identifier, "identifier")
        purchasePrice?.let { require(it >= 0) { "purchasePrice must not be negative" } }
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
        unitPrice?.let { require(it >= 0) { "unitPrice must not be negative" } }
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

/** Setting [isResolved] records the current time as resolution time; an existing resolution time is kept. */
@Serializable
data class NewDamageReport(
    val itemId: Uuid,
    val description: String,
    val reportedBy: String? = null,
    val isResolved: Boolean = false,
    val resolutionNotes: String? = null
) {
    init {
        requireNotBlank(description, "description")
    }
}

// ========= Kits =========

@Serializable
data class Kit(
    val id: Uuid,
    val name: String,
    val description: String?,
    val groups: List<KitGroup>
)

@Serializable
data class NewKit(
    val name: String,
    val description: String? = null,
    val groups: List<NewKitGroup>
) {
    init {
        requireNotBlank(name, "name")
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
    val id: Uuid,
    val name: String?,
    val requiredUnits: Double,
    val isOptional: Boolean,
    val options: List<KitGroupOption>
)

@Serializable
data class NewKitGroup(
    val name: String? = null,
    val requiredUnits: Double,
    val isOptional: Boolean = false,
    val options: List<KitGroupOption>
) {
    init {
        require(requiredUnits > 0) { "requiredUnits must be positive" }
        require(options.isNotEmpty()) { "a group needs at least one option" }
        require(options.distinctBy { it.materialId }.size == options.size) { "a material can only appear once per group" }
    }
}

/** How much one piece of a material contributes to a group. Options with lower [priority] are used first. */
@Serializable
data class KitGroupOption(
    val materialId: Uuid,
    val unitValue: Double = 1.0,
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
        requireNotBlank(borrower, "borrower")
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

private fun requireNotBlank(value: String, field: String) = require(value.isNotBlank()) { "$field must not be blank" }

private fun requireNonNegative(value: Int, field: String) = require(value >= 0) { "$field must not be negative" }
