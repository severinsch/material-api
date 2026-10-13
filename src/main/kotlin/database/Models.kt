package database

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import org.postgresql.util.PGobject

// Mappings of the schema defined in db/schema.sql, which is the source of truth and also holds all constraints.
// Columns maintained purely by the database (created_at, updated_at) are not mapped.

class PGEnum<T : Enum<T>>(enumTypeName: String, enumValue: T?) : PGobject() {
    init {
        value = enumValue?.name
        type = enumTypeName
    }
}

private inline fun <reified T : Enum<T>> Table.pgEnum(name: String, sqlType: String): Column<T> =
    customEnumeration(
        name = name,
        sql = sqlType,
        fromDb = { value -> enumValueOf<T>(value as String) },
        toDb = { PGEnum(sqlType, it) })

enum class MaterialTrackingType {
    BULK, INDIVIDUAL
}

enum class ItemCondition {
    NEW, GOOD, FAIR, POOR
}

enum class ItemStatus {
    ACTIVE, IN_REPAIR, LOST, DECOMMISSIONED
}

object StorageLocations : UUIDTable("storage_locations") {
    val name = varchar("name", 100).uniqueIndex()
    val description = text("description").nullable()
}

object Materials : UUIDTable("materials") {
    val name = varchar("name", 255).uniqueIndex()
    val trackingType = pgEnum<MaterialTrackingType>("tracking_type", "material_tracking_type")
    val description = text("description").nullable()
    val category = varchar("category", 100).nullable()
}

object IndividualItems : UUIDTable("individual_items") {
    val materialId = reference("material_id", Materials)
    val trackingType = pgEnum<MaterialTrackingType>("tracking_type", "material_tracking_type")
        .default(MaterialTrackingType.INDIVIDUAL)
    val identifier = varchar("identifier", 100)
    val condition = pgEnum<ItemCondition>("condition", "item_condition")
    val status = pgEnum<ItemStatus>("status", "item_status").default(ItemStatus.ACTIVE)
    val storageLocationId = reference("storage_location_id", StorageLocations).nullable()
    val locationDetail = varchar("location_detail", 255).nullable()
    val purchasePrice = decimal("purchase_price", 10, 2).nullable()
    val purchaseDate = date("purchase_date").nullable()
}

object BulkStock : Table("bulk_stock") {
    val materialId = reference("material_id", Materials)
    val trackingType = pgEnum<MaterialTrackingType>("tracking_type", "material_tracking_type")
        .default(MaterialTrackingType.BULK)
    val quantityAvailable = integer("quantity_available").default(0)
    val quantityDamaged = integer("quantity_damaged").default(0)
    val storageLocationId = reference("storage_location_id", StorageLocations).nullable()
    val locationDetail = varchar("location_detail", 255).nullable()
    override val primaryKey = PrimaryKey(materialId)
}

object Purchases : UUIDTable("purchases") {
    val materialId = reference("material_id", BulkStock.materialId)
    val quantity = integer("quantity")
    val unitPrice = decimal("unit_price", 10, 2).nullable()
    val purchaseDate = date("purchase_date").nullable()
    val note = text("note").nullable()
}

object DamageReports : UUIDTable("damage_reports") {
    val individualItemId = reference("individual_item_id", IndividualItems)
    val description = text("description")
    val reportedBy = varchar("reported_by", 255).nullable()
    val reportedAt = timestamp("reported_at").databaseGenerated()
    val resolvedAt = timestamp("resolved_at").nullable()
    val resolutionNotes = text("resolution_notes").nullable()
}

object Kits : UUIDTable("kits") {
    val name = varchar("name", 255).uniqueIndex()
    val description = text("description").nullable()
}

object KitGroups : UUIDTable("kit_groups") {
    val kitId = reference("kit_id", Kits, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255).nullable()
    val requiredUnits = decimal("required_units", 10, 2)
    val isOptional = bool("is_optional").default(false)
}

object KitGroupOptions : Table("kit_group_options") {
    val groupId = reference("group_id", KitGroups, onDelete = ReferenceOption.CASCADE)
    val materialId = reference("material_id", Materials)
    val unitValue = decimal("unit_value", 10, 2)
    val priority = integer("priority").default(0)
    override val primaryKey = PrimaryKey(groupId, materialId)
}

object Loans : UUIDTable("loans") {
    val borrower = varchar("borrower", 255)
    val lentAt = date("lent_at")
    val dueAt = date("due_at").nullable()
    val returnedAt = date("returned_at").nullable()
    val note = text("note").nullable()
}

object LoanLines : UUIDTable("loan_lines") {
    val loanId = reference("loan_id", Loans, onDelete = ReferenceOption.CASCADE)
    val individualItemId = reference("individual_item_id", IndividualItems).nullable()
    val bulkMaterialId = reference("bulk_material_id", BulkStock.materialId).nullable()
    val quantity = integer("quantity").nullable()
}
