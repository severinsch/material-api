package routes

import database.ItemCondition
import database.MaterialTrackingType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object UuidToStringSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("uuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Uuid =
        Uuid.parse(decoder.decodeString())
}

@Serializable
data class StorageLocation @OptIn(ExperimentalUuidApi::class) constructor(
    @Serializable(with = UuidToStringSerializer::class)
    val id: Uuid,
    val name: String,
    val description: String?
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed interface MaterialResponse {
    @OptIn(ExperimentalUuidApi::class)
    @Serializable(with = UuidToStringSerializer::class)
    val id: Uuid
    val name: String
    val trackingType: MaterialTrackingType
    val category: String?
    val description: String?
    val location: StorageLocation?
    val availableCount: Int
    val damagedCount: Int
}


@Serializable
data class BulkMaterialResponse @OptIn(ExperimentalUuidApi::class) constructor(
    @Serializable(with = UuidToStringSerializer::class)
    override val id: Uuid,
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
data class IndividualMaterialSummaryResponse @OptIn(ExperimentalUuidApi::class) constructor(
    @Serializable(with = UuidToStringSerializer::class)
    /**
     * @format uuid
     */
    override val id: Uuid,
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
data class NewMaterial @OptIn(ExperimentalUuidApi::class) constructor(
    val name: String,
    val trackingType: MaterialTrackingType,
    val category: String?,
    val description: String?,
)

@Serializable
data class NewBulkMaterial @OptIn(ExperimentalUuidApi::class) constructor(
    val name: String,
    @Serializable(with = UuidToStringSerializer::class)
    val storageLocationId: Uuid,
    val category: String?,
    val description: String?,
    val availableCount: Int,
    val damagedCount: Int,
    val purchaseDate: String?,
    val purchasePrice: Double?
)


@Serializable
data class IndividualItemResponse @OptIn(ExperimentalUuidApi::class) constructor(
    @Serializable(with = UuidToStringSerializer::class)
    val id: Uuid,
    @Serializable(with = UuidToStringSerializer::class)
    val materialId: Uuid,
    val identifier: String,
    val condition: ItemCondition,
    val storageLocation: StorageLocation?,
    val purchasePrice: Double?,
    val purchaseDate: String?
)

@Serializable
data class NewIndividualItem @OptIn(ExperimentalUuidApi::class) constructor(
    @Serializable(with = UuidToStringSerializer::class)
    val materialId: Uuid,
    val identifier: String,
    val condition: ItemCondition,
    val storageLocation: StorageLocation?,
    val purchasePrice: Double?,
    val purchaseDate: String?
)