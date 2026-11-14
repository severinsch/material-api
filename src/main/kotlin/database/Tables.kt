package database

import api.ItemCondition
import api.ItemStatus
import api.LoginMethod
import api.MaterialTrackingType
import api.TaskLabel
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
    val archivedAt = timestamp("archived_at").nullable()
}

/** Every material has at least one variant; stock, items, purchases and loans belong to a variant. */
object MaterialVariants : UuidTable("material_variants") {
    val materialId = reference("material_id", Materials, onDelete = ReferenceOption.CASCADE)
    val trackingType = pgEnum<MaterialTrackingType>("tracking_type", "material_tracking_type")
    val name = varchar("name", 100).nullable()
}

object IndividualItems : UuidTable("individual_items") {
    val variantId = reference("variant_id", MaterialVariants)
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
    val variantId = reference("variant_id", MaterialVariants, onDelete = ReferenceOption.CASCADE)
    val trackingType = pgEnum<MaterialTrackingType>("tracking_type", "material_tracking_type")
        .default(MaterialTrackingType.BULK)
    val quantityAvailable = integer("quantity_available")
    val quantityDamaged = integer("quantity_damaged")
    val storageLocationId = optReference("storage_location_id", StorageLocations)
    val locationDetail = varchar("location_detail", 255).nullable()
    override val primaryKey = PrimaryKey(variantId)
}

object Purchases : UuidTable("purchases") {
    // The schema references bulk_stock (BULK variants only); Exposed can only wrap ids of id tables.
    val variantId = reference("variant_id", MaterialVariants)
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
}

object KitGroupAmounts : Table("kit_group_amounts") {
    val groupId = reference("group_id", KitGroups, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val label = varchar("label", 100).nullable()
    val requiredUnits = integer("required_units")
    override val primaryKey = PrimaryKey(groupId, position)
}

object KitGroupOptions : Table("kit_group_options") {
    val groupId = reference("group_id", KitGroups, onDelete = ReferenceOption.CASCADE)
    val materialId = reference("material_id", Materials)
    /** Null for any variant of the material, including ones added later. */
    val variantId = optReference("variant_id", MaterialVariants)
    val unitValue = integer("unit_value")
    val priority = integer("priority")
}

object Loans : UuidTable("loans") {
    /** For members their name as of the last change to the loan, only shown after their account was deleted. */
    val borrower = varchar("borrower", 255)
    val borrowerId = optReference("borrower_id", Users, onDelete = ReferenceOption.SET_NULL)
    val lentAt = date("lent_at")
    val dueAt = date("due_at").nullable()
    val returnedAt = date("returned_at").nullable()
    val note = text("note").nullable()
}

object LoanLines : UuidTable("loan_lines") {
    val loanId = reference("loan_id", Loans, onDelete = ReferenceOption.CASCADE)
    val individualItemId = optReference("individual_item_id", IndividualItems)
    val variantId = optReference("variant_id", MaterialVariants)
    val quantity = integer("quantity").nullable()
}

// ========= Tasks =========

object TaskRules : UuidTable("task_rules") {
    val title = varchar("title", 255)
    val materialId = reference("material_id", Materials)
    val repeatMonths = integer("repeat_months")
    val fromPurchase = bool("from_purchase")
    val assigneeId = optReference("assignee_id", Users, onDelete = ReferenceOption.SET_NULL)
    val label = pgEnum<TaskLabel>("label", "task_label")
    val note = text("note").nullable()
}

object TaskRoutines : UuidTable("task_routines") {
    val name = varchar("name", 100)
    val anchor = varchar("anchor", 100)
}

object TaskRoutineSteps : UuidTable("task_routine_steps") {
    val routineId = reference("routine_id", TaskRoutines, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val offsetDays = integer("offset_days")
    val title = varchar("title", 255)
    val note = text("note").nullable()
    val assigneeId = optReference("assignee_id", Users, onDelete = ReferenceOption.SET_NULL)
    val label = pgEnum<TaskLabel>("label", "task_label")
    val urgent = bool("urgent")
}

object TaskRoutineStepLinks : Table("task_routine_step_links") {
    val stepId = reference("step_id", TaskRoutineSteps, onDelete = ReferenceOption.CASCADE)
    val materialId = optReference("material_id", Materials)
    val itemId = optReference("item_id", IndividualItems)
}

object Tasks : UuidTable("tasks") {
    val title = varchar("title", 255)
    val note = text("note").nullable()
    val dueDate = date("due_date").nullable()
    val urgent = bool("urgent")
    val label = pgEnum<TaskLabel>("label", "task_label")
    val assigneeId = optReference("assignee_id", Users, onDelete = ReferenceOption.SET_NULL)
    val repeatMonths = integer("repeat_months").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val completedBy = varchar("completed_by", 255).nullable()
    val completionNote = text("completion_note").nullable()
    val previousId = optReference("previous_id", Tasks)
    val ruleId = optReference("rule_id", TaskRules)
    val routineId = optReference("routine_id", TaskRoutines)
    val occasion = varchar("occasion", 100).nullable()
    val damageReportId = optReference("damage_report_id", DamageReports)
}

object TaskLinks : Table("task_links") {
    val taskId = reference("task_id", Tasks, onDelete = ReferenceOption.CASCADE)
    val materialId = optReference("material_id", Materials)
    val itemId = optReference("item_id", IndividualItems)
}
