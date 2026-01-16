package routes

import database.ItemCondition
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
    // additional fields for bulk items, for individual items these are in the individual items
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null
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
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null
)

@Serializable
data class IndividualItem(
    val id: String,
    val materialId: String,
    val identifier: String,
    val condition: ItemCondition,
    val storageLocation: StorageLocation? = null,
    val purchasePrice: Double? = null,
    val purchaseDate: String? = null
)

@Serializable
data class NewIndividualItem(
    val materialId: String,
    val identifier: String,
    val condition: ItemCondition,
    val storageLocation: StorageLocation? = null,
    val purchasePrice: Double? = null,
    val purchaseDate: String? = null
)

@Serializable
data class DamageReport(
    val id: String,
    val inventoryItemId: String,
    val description: String,
    val isResolved: Boolean,
    val reportedAt: String
)

@Serializable
data class NewDamageReport(
    val inventoryItemId: String,
    val description: String,
    val isResolved: Boolean = false
)

@Serializable
data class Kit(
    val id: String,
    val name: String,
    val description: String? = null,
    val fixedMaterials: List<FixedMaterial>,
    val substitutionGroups: List<SubstitutionGroup>
)

@Serializable
data class NewKit(
    val name: String,
    val description: String? = null,
    val fixedMaterials: List<FixedMaterial>,
    val substitutionGroups: List<NewSubstitutionGroup>
)

@Serializable
data class FixedMaterial(
    val materialId: String,
    val quantity: Int
)

@Serializable
data class SubstitutionGroup(
    val id: String,
    val name: String,
    val isOptional: Boolean,
    val requiredUnits: Double,
    val minRequiredUnits: Double,
    val options: List<SubstitutionOption>
)

@Serializable
data class NewSubstitutionGroup(
    val name: String,
    val isOptional: Boolean,
    val requiredUnits: Double,
    val minRequiredUnits: Double,
    val options: List<SubstitutionOption>
)

@Serializable
data class SubstitutionOption(
    val materialId: String,
    val unitValue: Double,
    val isPreferred: Boolean
)