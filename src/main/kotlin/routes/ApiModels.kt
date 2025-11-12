package routes

import database.ItemCondition
import database.MaterialTrackingType
import kotlinx.serialization.Serializable

@Serializable
data class StorageLocation(
    val id: String,
    val name: String,
    val description: String?
)

@Serializable
sealed interface MaterialResponse {
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
data class BulkMaterialResponse(
    override val id: String,
    override val name: String,
    override val trackingType: MaterialTrackingType,
    override val category: String?,
    override val description: String?,
    override val location: StorageLocation?,
    override val availableCount: Int,
    override val damagedCount: Int,
    // additional fields for bulk items, for individual items these are in the individual items
    val purchaseDate: String?,
    val purchasePrice: Double?
) : MaterialResponse

@Serializable
data class IndividualMaterialSummaryResponse(
    override val id: String,
    override val name: String,
    override val trackingType: MaterialTrackingType,
    override val category: String?,
    override val description: String?,
    override val location: StorageLocation?,
    // These fields are calculated by counting rows in the `individual_items` table.
    override val availableCount: Int,
    override val damagedCount: Int
) : MaterialResponse

@Serializable
data class NewIndividualMaterial(
    val name: String,
    val trackingType: MaterialTrackingType,
    val category: String?,
    val description: String?,
)

@Serializable
data class NewBulkMaterial(
    val name: String,
    val storageLocationId: String,
    val category: String?,
    val description: String?,
    val availableCount: Int,
    val damagedCount: Int,
    val purchaseDate: String?,
    val purchasePrice: Double?
)


@Serializable
data class IndividualItemResponse(
    val id: String,
    val materialId: String,
    val identifier: String,
    val condition: ItemCondition,
    val storageLocation: StorageLocation?,
    val purchasePrice: Double?,
    val purchaseDate: String?
)

@Serializable
data class NewIndividualItem(
    val materialId: String,
    val identifier: String,
    val condition: ItemCondition,
    val storageLocation: StorageLocation?,
    val purchasePrice: Double?,
    val purchaseDate: String?
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