package database

import api.ItemCondition
import api.ItemStatus
import api.MaterialTrackingType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.postgresql.util.PGobject

// Mappings of the schema defined in db/schema.sql, which is the source of truth and also holds all constraints.
// Columns maintained purely by the database (created_at, updated_at) are not mapped.

private class PGEnum<T : Enum<T>>(enumTypeName: String, enumValue: T?) : PGobject() {
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

object StorageLocations : UuidTable("storage_locations") {
    val name = varchar("name", 100)
    val description = text("description").nullable()
}

object Materials : UuidTable("materials") {
    val name = varchar("name", 255)
    val trackingType = pgEnum<MaterialTrackingType>("tracking_type", "material_tracking_type")
    val description = text("description").nullable()
    val category = varchar("category", 100).nullable()
}

object IndividualItems : UuidTable("individual_items") {
    val materialId = reference("material_id", Materials)
    val trackingType = pgEnum<MaterialTrackingType>("tracking_type", "material_tracking_type")
        .default(MaterialTrackingType.INDIVIDUAL)
    val identifier = varchar("identifier", 100)
    val condition = pgEnum<ItemCondition>("condition", "item_condition")
    val status = pgEnum<ItemStatus>("status", "item_status")
    val storageLocationId = optReference("storage_location_id", StorageLocations)
    val locationDetail = varchar("location_detail", 255).nullable()
    val purchasePrice = decimal("purchase_price", 10, 2).nullable()
    val purchaseDate = date("purchase_date").nullable()
}

object BulkStock : Table("bulk_stock") {
    val materialId = reference("material_id", Materials)
    val trackingType = pgEnum<MaterialTrackingType>("tracking_type", "material_tracking_type")
        .default(MaterialTrackingType.BULK)
    val quantityAvailable = integer("quantity_available")
    val quantityDamaged = integer("quantity_damaged")
    val storageLocationId = optReference("storage_location_id", StorageLocations)
    val locationDetail = varchar("location_detail", 255).nullable()
    override val primaryKey = PrimaryKey(materialId)
}

object Purchases : UuidTable("purchases") {
    // The schema references bulk_stock (BULK materials only); Exposed can only wrap ids of id tables.
    val materialId = reference("material_id", Materials)
    val quantity = integer("quantity")
    val unitPrice = decimal("unit_price", 10, 2).nullable()
    val purchaseDate = date("purchase_date").nullable()
    val note = text("note").nullable()
}

object DamageReports : UuidTable("damage_reports") {
    val individualItemId = reference("individual_item_id", IndividualItems)
    val description = text("description")
    val reportedBy = varchar("reported_by", 255).nullable()
    val reportedAt = timestamp("reported_at").databaseGenerated()
    val resolvedAt = timestamp("resolved_at").nullable()
    val resolutionNotes = text("resolution_notes").nullable()
}

object Kits : UuidTable("kits") {
    val name = varchar("name", 255)
    val description = text("description").nullable()
}

object KitGroups : UuidTable("kit_groups") {
    val kitId = reference("kit_id", Kits, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val name = varchar("name", 255).nullable()
    val requiredUnits = integer("required_units")
    val isOptional = bool("is_optional")
}

object KitGroupOptions : Table("kit_group_options") {
    val groupId = reference("group_id", KitGroups, onDelete = ReferenceOption.CASCADE)
    val materialId = reference("material_id", Materials)
    val unitValue = integer("unit_value")
    val priority = integer("priority")
    override val primaryKey = PrimaryKey(groupId, materialId)
}

object Loans : UuidTable("loans") {
    val borrower = varchar("borrower", 255)
    val lentAt = date("lent_at")
    val dueAt = date("due_at").nullable()
    val returnedAt = date("returned_at").nullable()
    val note = text("note").nullable()
}

object LoanLines : UuidTable("loan_lines") {
    val loanId = reference("loan_id", Loans, onDelete = ReferenceOption.CASCADE)
    val individualItemId = optReference("individual_item_id", IndividualItems)
    // The schema references bulk_stock, see Purchases.
    val bulkMaterialId = optReference("bulk_material_id", Materials)
    val quantity = integer("quantity").nullable()
}
