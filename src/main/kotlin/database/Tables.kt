package database

import api.ItemCondition
import api.ItemStatus
import api.LoginMethod
import api.MaterialTrackingType
import api.TenantRole
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.postgresql.util.PGobject

// Mappings of the schema defined by the migrations in src/main/resources/db/migration, which are the source of truth and also holds all constraints.
// Columns maintained purely by the database (created_at, updated_at, and tenant_id of tenant-owned tables) are not mapped.

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

// ========= Tenants and accounts =========

object Tenants : UuidTable("tenants") {
    val name = varchar("name", 100)
    val slug = varchar("slug", 50)
    val deletedAt = timestamp("deleted_at").nullable()
}

object Users : UuidTable("users") {
    val displayName = varchar("display_name", 255)
    val isPlatformAdmin = bool("is_platform_admin")
    val disabledAt = timestamp("disabled_at").nullable()
}

object PasswordLogins : Table("password_logins") {
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val username = varchar("username", 100)
    val passwordHash = text("password_hash").nullable()
    override val primaryKey = PrimaryKey(userId)
}

object TenantMembers : Table("tenant_members") {
    val tenantId = reference("tenant_id", Tenants, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val role = pgEnum<TenantRole>("role", "tenant_role")
    override val primaryKey = PrimaryKey(tenantId, userId)
}

object Sessions : UuidTable("sessions") {
    val tokenHash = binary("token_hash")
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val loginMethod = enumerationByName<LoginMethod>("login_method", 20)
    val lastUsedAt = timestamp("last_used_at")
    val expiresAt = timestamp("expires_at")
}

object SetupTokens : Table("setup_tokens") {
    // The schema references password_logins (users with a password only); Exposed can only reference id tables.
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val tokenHash = binary("token_hash")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(userId)
}

// ========= Tenant-owned tables =========

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
    val materialId = optReference("material_id", Materials)
    val quantity = integer("quantity").nullable()
}
