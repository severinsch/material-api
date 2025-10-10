package database

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

enum class MaterialTrackingType {
    BULK, INDIVIDUAL
}

enum class ItemCondition {
    NEW, GOOD, FAIR, POOR, DECOMMISSIONED
}

object StorageLocations : UUIDTable("storage_locations") {
    val name = varchar("name", 255).uniqueIndex()
    val description = text("description").nullable()
}

object Materials : UUIDTable("materials") {
    val name = varchar("name", 255).uniqueIndex()
    val trackingType = enumerationByName("tracking_type", 20, MaterialTrackingType::class)
    val storageLocationId = reference("storage_location_id", StorageLocations).nullable()
    val quantityAvailable = integer("quantity_available").nullable()
    val quantityDamaged = integer("quantity_damaged").nullable()
    val description = text("description").nullable()
    val category = varchar("category", 255).nullable()
    val purchasePrice = decimal("purchase_price", 10, 2).nullable()
    val purchaseDate = date("purchase_date").nullable()
}

object InventoryItems : UUIDTable("inventory_items") {
    val materialId = reference("material_id", Materials)
    val storageLocationId = reference("storage_location_id", StorageLocations).nullable()
    val identifier = varchar("identifier", 255)
    val condition = enumerationByName("condition", 20, ItemCondition::class)

    init {
        uniqueIndex(materialId, identifier)
    }
}

object DamageReports : UUIDTable("damage_reports") {
    val inventoryItemId = reference("inventory_item_id", InventoryItems)
    val description = text("description")
    val isResolved = bool("is_resolved").default(false)
    val reportedAt = timestamp("reported_at")
}

object Kits : UUIDTable("kits") {
    val name = varchar("name", 255).uniqueIndex()
    val description = text("description").nullable()
}

object KitMaterials : Table("kit_materials") {
    val kitId = reference("kit_id", Kits)
    val materialId = reference("material_id", Materials)
    val quantityRequired = integer("quantity_required")

    override val primaryKey = PrimaryKey(kitId, materialId)
}

