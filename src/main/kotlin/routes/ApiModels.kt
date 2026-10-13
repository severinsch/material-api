package routes

import database.ItemCondition
import database.ItemStatus
import database.MaterialTrackingType
import kotlinx.serialization.Serializable

@Serializable
data class NewStorageLocation(
    val name: String,
    val description: String? = null
)

@Serializable
data class StorageLocation(
    val id: String,
    val name: String,
    val description: String? = null
)

@Serializable
sealed interface Material {
    val id: String
    val name: String
    val trackingType: MaterialTrackingType
    val category: String?
    val description: String?
    val location: StorageLocation?
    val availableCount: Int
    val damagedCount: Int
}


@Serializable
data class BulkMaterial(
    override val id: String,
    override val name: String,
    override val trackingType: MaterialTrackingType,
    override val category: String? = null,
    override val description: String? = null,
    override val location: StorageLocation? = null,
    override val availableCount: Int,
    override val damagedCount: Int,
    val locationDetail: String? = null
) : Material

@Serializable
data class IndividualMaterialSummary(
    override val id: String,
    override val name: String,
    override val trackingType: MaterialTrackingType,
    override val category: String? = null,
    override val description: String? = null,
    override val location: StorageLocation? = null,
    // These fields are calculated by counting rows in the `individual_items` table.
    override val availableCount: Int,
    override val damagedCount: Int
) : Material

@Serializable
data class NewIndividualMaterial(
    val name: String,
    val category: String? = null,
    val description: String? = null,
)

@Serializable
data class NewBulkMaterial(
    val name: String,
    val storageLocationId: String? = null,
    val category: String? = null,
    val description: String? = null,
    val availableCount: Int,
    val damagedCount: Int,
    val locationDetail: String? = null
)

@Serializable
data class IndividualItem(
    val id: String,
    val materialId: String,
    val identifier: String,
    val condition: ItemCondition,
    val status: ItemStatus,
    val storageLocation: StorageLocation? = null,
    val locationDetail: String? = null,
    val purchasePrice: Double? = null,
    val purchaseDate: String? = null
)

@Serializable
data class NewIndividualItem(
    val materialId: String,
    val identifier: String,
    val condition: ItemCondition,
    val status: ItemStatus = ItemStatus.ACTIVE,
    val storageLocation: StorageLocation? = null,
    val locationDetail: String? = null,
    val purchasePrice: Double? = null,
    val purchaseDate: String? = null
)

@Serializable
data class DamageReport(
    val id: String,
    val inventoryItemId: String,
    val description: String,
    val isResolved: Boolean,
    val reportedBy: String? = null,
    val reportedAt: String,
    val resolvedAt: String? = null,
    val resolutionNotes: String? = null
)

@Serializable
data class NewDamageReport(
    val inventoryItemId: String,
    val description: String,
    val isResolved: Boolean = false,
    val reportedBy: String? = null,
    val resolutionNotes: String? = null
)

@Serializable
data class Kit(
    val id: String,
    val name: String,
    val description: String? = null,
    val groups: List<KitGroup>
)

@Serializable
data class NewKit(
    val name: String,
    val description: String? = null,
    val groups: List<NewKitGroup>
)

/**
 * A slot in a kit that needs [requiredUnits] units, filled by one or more interchangeable materials.
 * A fixed material ("8x Fenster") is a group with a single option of unit value 1 and no name.
 * Optional groups are all-or-nothing: packed completely or not at all.
 */
@Serializable
data class KitGroup(
    val id: String,
    val name: String? = null,
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
)

/** How much one piece of a material contributes to a group. Options with lower [priority] are used first. */
@Serializable
data class KitGroupOption(
    val materialId: String,
    val unitValue: Double = 1.0,
    val priority: Int = 0
)
