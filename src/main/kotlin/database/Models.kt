package database

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import org.postgresql.util.PGobject


class PGEnum<T : Enum<T>>(enumTypeName: String, enumValue: T?) : PGobject() {
    init {
        value = enumValue?.name
        type = enumTypeName
    }
}

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
    val trackingType: Column<MaterialTrackingType> = customEnumeration(
        name = "tracking_type",
        sql = "material_tracking_type",
        fromDb = { value -> MaterialTrackingType.valueOf(value as String) },
        toDb = { PGEnum("material_tracking_type", it) })
    val description = text("description").nullable()
    val category = varchar("category", 255).nullable()
}

object IndividualItems : UUIDTable("individual_items") {
    val materialId = reference("material_id", Materials)
    val identifier = varchar("identifier", 255)
    val condition: Column<ItemCondition> = customEnumeration(
        name = "condition",
        sql = "item_condition",
        fromDb = { value -> ItemCondition.valueOf(value as String) },
        toDb = { PGEnum("item_condition", it) })
    val storageLocationId = reference("storage_location_id", StorageLocations).nullable()
    val purchasePrice = decimal("purchase_price", 10, 2).nullable()
    val purchaseDate = date("purchase_date").nullable()

    init {
        uniqueIndex(materialId, identifier)
    }
}

object BulkStock : UUIDTable("bulk_stock") {
    val materialId = reference("material_id", Materials)
    val quantityAvailable = integer("quantity_available").default(0)
    val quantityDamaged = integer("quantity_damaged").default(0)
    val storageLocationId = reference("storage_location_id", StorageLocations).nullable()
    val purchasePrice = decimal("purchase_price", 10, 2).nullable()
    val purchaseDate = date("purchase_date").nullable()

    init {
        uniqueIndex(materialId)
    }
}

object DamageReports : UUIDTable("damage_reports") {
    val inventoryItemId = reference("individual_item_id", IndividualItems)
    val description = text("description")
    val isResolved = bool("is_resolved").default(false)
    val reportedAt = timestamp("reported_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val reportedBy = varchar("reported_by", 255).nullable()
    val resolutionNotes = text("resolution_notes").nullable()
}

object Kits : UUIDTable("kits") {
    val name = varchar("name", 255).uniqueIndex()
    val description = text("description").nullable()
}

object KitFixedMaterials : Table("kit_fixed_materials") {
    val kitId = reference("kit_id", Kits, onDelete = ReferenceOption.CASCADE)
    val materialId = reference("material_id", Materials, onDelete = ReferenceOption.CASCADE)
    val quantityRequired = integer("quantity_required")
    override val primaryKey = PrimaryKey(kitId, materialId)
}

object KitSubstitutionGroups : UUIDTable("kit_substitution_groups") {
    val kitId = reference("kit_id", Kits, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val requiredUnits = decimal("required_units", 10, 2)
    val minRequiredUnits = decimal("min_required_units", 10, 2).default(0.0.toBigDecimal())
    val isOptional = bool("is_optional").default(false)
}

object KitSubstitutionOptions : Table("kit_substitution_options") {
    val groupId = reference("group_id", KitSubstitutionGroups, onDelete = ReferenceOption.CASCADE)
    val materialId = reference("material_id", Materials, onDelete = ReferenceOption.CASCADE)
    val unitValue = decimal("unit_value", 10, 2)
    val isPreferred = bool("is_preferred").default(false)
    override val primaryKey = PrimaryKey(groupId, materialId)
}