package routes

import database.ItemCondition
import database.MaterialTrackingType
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class StorageLocation @OptIn(ExperimentalUuidApi::class) constructor(
    val id: Uuid,
    val name: String,
    val description: String?
)

@Serializable
data class MaterialSummaryResponse @OptIn(ExperimentalUuidApi::class) constructor(
    val id: Uuid,
    val name: String,
    val trackingType: MaterialTrackingType,
    val category: String?,
    val description: String?,
    val location: StorageLocation?,

    // These fields are polymorphic and always populated.
    // For BULK: They come directly from the `bulk_stock` table.
    // For INDIVIDUAL: They are calculated by counting rows in the `individual_items` table.
    val availableCount: Int,
    val damagedCount: Int
)

@Serializable
data class IndividualItemResponse @OptIn(ExperimentalUuidApi::class) constructor(
    val id: Uuid,
    val materialId: Uuid,
    val identifier: String,
    val condition: ItemCondition,
    val storageLocation: StorageLocation?,
    val purchasePrice: Double?,
    val purchaseDate: String?
)