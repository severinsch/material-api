package services

import api.*
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import org.postgresql.util.PSQLException
import kotlin.uuid.Uuid

/** Foreign keys that refuse deleting a material because of its history: items, purchases and loans. */
internal val HISTORY_CONSTRAINTS = setOf("individual_items_variant_id_fkey", "purchases_variant_id_fkey", "loan_lines_variant_id_fkey")

/** Materials are written together with their complete list of variants, see [NewMaterial]. */
class MaterialService(private val db: Db) {

    private companion object {
        const val MATERIAL_CHANGED = "Das Material wurde inzwischen geändert. $RELOAD"
        const val MATERIALS_CHANGED = "Die Materialien wurden inzwischen geändert. $RELOAD"
    }

    private data class Counts(val available: Int = 0, val damaged: Int = 0)

    private data class VariantRow(val id: Uuid, val materialId: Uuid, val name: String?)

    suspend fun getAll(tenant: TenantId, type: MaterialTrackingType? = null): List<Material> = db.tenantQuery(tenant) {
        findWhere { type?.let { Materials.trackingType eq it } ?: Op.TRUE }
    }

    suspend fun get(tenant: TenantId, id: Uuid): Material = db.tenantQuery(tenant) { find(id) }

    suspend fun create(tenant: TenantId, material: NewMaterial): Material = db.tenantQuery(tenant) {
        if (material.listedVariants.any { it.first != null } || material.removedVariantIds.isNotEmpty()) {
            invalid("Ein neues Material hat noch keine Varianten, die geändert oder entfernt werden können.")
        }
        requireLocations(material)
        val id = Materials.insertAndGetId {
            it[trackingType] = material.trackingType
            it.fillMaterial(material)
        }
        insertVariants(id, material)
        find(id.value)
    }

    /**
     * Replaces a material including its variants. Its type can't be changed. Archived materials can be edited like
     * others, including their variants and stock.
     */
    suspend fun update(tenant: TenantId, id: Uuid, material: NewMaterial): Material = db.tenantQuery(tenant) {
        lockMaterial(id)
        if (find(id).trackingType != material.trackingType) conflict("Die Erfassungsart eines Materials kann nicht geändert werden.")
        val existing = lockVariantsOf(listOf(id)).associate { it.id to it.name }
        val listed = material.listedVariants.mapNotNull { it.first }
        val unknown = (listed + material.removedVariantIds).filter { it !in existing }
        if (unknown.isNotEmpty()) {
            existingVariants(unknown).firstOrNull()?.let { invalid("${variantName(it)} gehört nicht zu diesem Material. $RELOAD") }
            // Removed meanwhile, e.g. by another user.
            conflict(MATERIAL_CHANGED)
        }
        // E.g. a variant another user added meanwhile, which must not disappear unnoticed.
        if (existing.keys.any { it !in listed && it !in material.removedVariantIds }) conflict(MATERIAL_CHANGED)
        requireLocations(material)
        if (material is NewBulkMaterial) requireStockUpdates(material)

        removeVariants(material.removedVariantIds.associateWith { existing.getValue(it) })
        Materials.update({ Materials.id eq id }) { it.fillMaterial(material) }
        updateVariants(material, existing)
        insertVariants(EntityID(id, Materials), material)
        find(id)
    }

    /**
     * Deletes a material with its variants, only while nothing references them; the foreign keys refuse it otherwise.
     * Instead of deleting a material with items, purchases or loans, clients can offer to archive it: that conflict has
     * [ErrorCode.ARCHIVE_INSTEAD].
     */
    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        try {
            if (Materials.deleteWhere { Materials.id eq id } == 0) notFound("Dieses Material")
        } catch (e: ExposedSQLException) {
            val constraint = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<PSQLException>()
                .firstNotNullOfOrNull { it.serverErrorMessage?.constraint }
            if (constraint !in HISTORY_CONSTRAINTS) throw e
            conflict(
                "Das Material hat Gegenstände, Einkäufe oder Ausleihen und kann deshalb nicht gelöscht werden.",
                ErrorCode.ARCHIVE_INSTEAD
            )
        }
    }

    /**
     * Archives a material: it keeps its history and is hidden where new use starts (see [Material]). Materials used in
     * kits can't be archived. Archiving an archived material keeps the original time.
     */
    suspend fun archive(tenant: TenantId, id: Uuid): Material = db.tenantQuery(tenant) {
        kitsUsing(id).takeIf { it.isNotEmpty() }?.let { kits ->
            conflict("Das Material wird ${inKits(kits)} verwendet. Entferne es zuerst daraus.")
        }
        Materials.update({ (Materials.id eq id) and Materials.archivedAt.isNull() }) {
            it[archivedAt] = Clock.System.now()
        }
        find(id)
    }

    suspend fun unarchive(tenant: TenantId, id: Uuid): Material = db.tenantQuery(tenant) {
        Materials.update({ (Materials.id eq id) and Materials.archivedAt.isNotNull() }) { it[archivedAt] = null }
        find(id)
    }

    /**
     * Locks a material against concurrent changes. Locks are taken in the order materials, then variants (see
     * [lockVariantsOf]), in every path that takes both.
     */
    private fun lockMaterial(id: Uuid) {
        Materials.select(Materials.id).where { Materials.id eq id }.forUpdate().singleOrNull() ?: notFound("Dieses Material")
    }

    /** Names of the kits that use any variant of the material, sorted. */
    private fun kitsUsing(materialId: Uuid): List<String> = KitGroupOptions.innerJoin(KitGroups).innerJoin(Kits)
        .select(Kits.name)
        .where { KitGroupOptions.materialId eq materialId }
        .map { it[Kits.name] }
        .distinct()
        .sorted()

    /**
     * Merges materials into the first one, see [MaterialMerge]. Everything that references a variant comes along
     * unchanged; kit options for a specific variant follow it through their cascading key.
     */
    suspend fun merge(tenant: TenantId, merge: MaterialMerge): Material = db.tenantQuery(tenant) {
        val targetId = merge.materialIds.first()
        val sourceIds = merge.materialIds.drop(1)

        // Materials first, then their variants, as everywhere else. With the materials locked, nobody can add
        // variants or kit options to them meanwhile (foreign keys lock the referenced rows).
        val materials = Materials.select(Materials.id, Materials.name, Materials.trackingType, Materials.archivedAt)
            .where { Materials.id inList merge.materialIds }
            .orderBy(Materials.id)
            .forUpdate()
            .associateBy { it[Materials.id].value }
        // The client lists materials it has seen, so a missing one was most likely deleted or merged meanwhile.
        if (merge.materialIds.any { it !in materials }) conflict(MATERIALS_CHANGED)
        if (materials.values.map { it[Materials.trackingType] }.distinct().size > 1) {
            invalid("Nur Materialien derselben Erfassungsart können zusammengeführt werden.")
        }
        // Merging would quietly bring the archived material's variants back, or archive the others' ones.
        materials.values.firstOrNull { it[Materials.archivedAt] != null }?.let {
            conflict("${it[Materials.name]} ist archiviert. Hole das Material zuerst aus dem Archiv, um es zusammenzuführen.")
        }
        val variants = lockVariantsOf(merge.materialIds)

        val unknown = merge.variantNames.keys.filter { id -> variants.none { it.id == id } }
        if (unknown.isNotEmpty()) {
            existingVariants(unknown).firstOrNull()?.let {
                invalid("${variantName(it)} gehört zu keinem der zusammengeführten Materialien. $RELOAD")
            }
            conflict(MATERIALS_CHANGED)
        }
        val names = variants.associate { it.id to (merge.variantNames[it.id] ?: it.name) }
        if (names.values.any { it == null }) invalid("Hat ein Material mehrere Varianten, braucht jede einen Namen.")
        if (names.values.toSet().size < names.size) invalid("Die Varianten eines Materials brauchen unterschiedliche Namen.")

        // "Any variant" becomes the current variants of its material, so every kit uses exactly what it did before.
        val variantsByMaterial = variants.groupBy({ it.materialId }) { it.id }
        val anyVariant = KitGroupOptions.selectAll()
            .where { (KitGroupOptions.materialId inList merge.materialIds) and KitGroupOptions.variantId.isNull() }
            .toList()
        KitGroupOptions.batchInsert(
            anyVariant.flatMap { option -> variantsByMaterial[option[KitGroupOptions.materialId].value].orEmpty().map { option to it } }
        ) { (option, variantId) ->
            this[KitGroupOptions.groupId] = option[KitGroupOptions.groupId]
            this[KitGroupOptions.materialId] = option[KitGroupOptions.materialId]
            this[KitGroupOptions.variantId] = variantId
            this[KitGroupOptions.unitValue] = option[KitGroupOptions.unitValue]
            this[KitGroupOptions.priority] = option[KitGroupOptions.priority]
        }
        KitGroupOptions.deleteWhere { (KitGroupOptions.materialId inList merge.materialIds) and KitGroupOptions.variantId.isNull() }
        // Names are unique per material and may be swapped or taken over, so they are cleared first.
        val variantIds = variants.map { it.id }
        MaterialVariants.update({ MaterialVariants.id inList variantIds }) { it[MaterialVariants.name] = null }
        MaterialVariants.update({ MaterialVariants.materialId inList sourceIds }) {
            it[MaterialVariants.materialId] = EntityID(targetId, Materials)
        }
        // Tasks, routine steps and rules about a merged material are about the remaining one now, instead of losing
        // the link with the deleted material.
        moveMaterialLinks(TaskLinks, TaskLinks.taskId, TaskLinks.materialId, targetId, sourceIds)
        moveMaterialLinks(TaskRoutineStepLinks, TaskRoutineStepLinks.stepId, TaskRoutineStepLinks.materialId, targetId, sourceIds)
        TaskRules.update({ TaskRules.materialId inList sourceIds }) { it[TaskRules.materialId] = EntityID(targetId, Materials) }
        // The rules of each merged material now cover the other materials' items too.
        val items = IndividualItems.select(IndividualItems.id).where { IndividualItems.variantId inList variantIds }
        createMissingRuleTasks(tenant, items.map { it[IndividualItems.id].value })
        Materials.deleteWhere { Materials.id inList sourceIds }
        Materials.update({ Materials.id eq targetId }) { it[Materials.name] = merge.name }
        names.forEach { (id, name) ->
            MaterialVariants.update({ MaterialVariants.id eq id }) { it[MaterialVariants.name] = name }
        }
        find(targetId)
    }

    /** Points links of [owner]s (tasks or routine steps) to [sourceIds] to [targetId], one link per owner. */
    private fun moveMaterialLinks(
        table: Table,
        owner: Column<EntityID<Uuid>>,
        material: Column<EntityID<Uuid>?>,
        targetId: Uuid,
        sourceIds: List<Uuid>
    ) {
        val links = table.select(owner, material).where { material inList (sourceIds + targetId) }
            .groupBy({ it[owner].value }) { it[material]!!.value }
        for ((ownerId, materialIds) in links) {
            val keep = if (targetId in materialIds) null else materialIds.first()
            table.deleteWhere { (owner eq ownerId) and (material inList sourceIds) and (keep?.let { material neq it } ?: Op.TRUE) }
            if (keep != null) table.update({ (owner eq ownerId) and (material eq keep) }) { it[material] = EntityID(targetId, Materials) }
        }
    }

    /**
     * Locks the variants of materials (in the same order as [lockVariants]) and returns them. Callers that lock
     * materials as well lock them first, see [lockMaterial].
     */
    private fun lockVariantsOf(materialIds: Collection<Uuid>): List<VariantRow> = MaterialVariants
        .select(MaterialVariants.id, MaterialVariants.materialId, MaterialVariants.name)
        .where { MaterialVariants.materialId inList materialIds }
        .orderBy(MaterialVariants.id)
        .forUpdate()
        .map { VariantRow(it[MaterialVariants.id].value, it[MaterialVariants.materialId].value, it[MaterialVariants.name]) }

    /** Those of [variantIds] that exist, as a variant of any material of the tenant. */
    private fun existingVariants(variantIds: List<Uuid>): List<Uuid> = MaterialVariants
        .select(MaterialVariants.id)
        .where { MaterialVariants.id inList variantIds }
        .map { it[MaterialVariants.id].value }

    /** E.g. "im Set Jurte" or "in den Sets Jurte, Kohte". */
    private fun inKits(names: List<String>) =
        if (names.size == 1) "im Set ${names.single()}" else "in den Sets ${names.joinToString()}"

    private fun requireLocations(material: NewMaterial) {
        if (material is NewBulkMaterial) material.variants.mapNotNull { it.storageLocationId }.distinct().forEach(::requireLocation)
    }

    /**
     * Rejects stock changes of existing variants based on an outdated count, and stocks below what is lent out. The
     * variants are locked already.
     */
    private fun requireStockUpdates(material: NewBulkMaterial) {
        val updated = material.variants.filter { it.id != null }
        val ids = updated.map { it.id!! }
        val stock = lockStock(ids)
        val loaned = loanedCounts(ids)
        for (variant in updated) {
            val name = displayName(material.name, variant.name)
            val current = stock.getValue(variant.id!!)
            if (variant.expectedAvailableCount != null && variant.expectedAvailableCount != current) {
                conflict(
                    "Der Bestand von $name wurde inzwischen auf $current geändert, z. B. durch einen Einkauf. " +
                        "Lade die Seite neu und bearbeite das Material erneut."
                )
            }
            val lent = loaned[variant.id] ?: 0
            if (variant.availableCount < lent) {
                conflict("Von $name sind $lent ausgeliehen, der Bestand kann nicht kleiner sein.")
            }
        }
    }

    /**
     * Deletes variants (ids with their names) that nothing references. Their stock goes with them; everything else
     * has history and is a conflict, with a message that says what to do.
     */
    private fun removeVariants(variants: Map<Uuid, String?>) {
        if (variants.isEmpty()) return
        val ids = variants.keys
        fun label(id: EntityID<Uuid>) = "Die Variante ${variants.getValue(id.value) ?: "ohne Namen"}"

        IndividualItems.select(IndividualItems.variantId).where { IndividualItems.variantId inList ids }.firstOrNull()?.let {
            conflict(
                "${label(it[IndividualItems.variantId])} hat noch Gegenstände. " +
                    "Lösche sie zuerst oder ordne sie einer anderen Variante zu."
            )
        }
        Purchases.select(Purchases.variantId).where { Purchases.variantId inList ids }.firstOrNull()?.let {
            conflict("${label(it[Purchases.variantId])} hat Einkäufe. Lösche zuerst die Einkäufe.")
        }
        LoanLines.select(LoanLines.variantId).where { LoanLines.variantId inList ids }.firstOrNull()?.let {
            conflict("${label(it[LoanLines.variantId]!!)} wurde schon ausgeliehen und kann nicht entfernt werden.")
        }
        val kitsByVariant = KitGroupOptions.innerJoin(KitGroups).innerJoin(Kits)
            .select(KitGroupOptions.variantId, Kits.name)
            .where { KitGroupOptions.variantId inList ids }
            .groupBy({ it[KitGroupOptions.variantId]!! }, { it[Kits.name] })
        kitsByVariant.entries.firstOrNull()?.let { (id, kits) ->
            conflict("${label(id)} wird ${inKits(kits.distinct().sorted())} verwendet. Entferne sie zuerst daraus.")
        }
        MaterialVariants.deleteWhere { MaterialVariants.id inList ids }
    }

    /** Updates the listed variants that have an id; [existing] are their current names. */
    private fun updateVariants(material: NewMaterial, existing: Map<Uuid, String?>) {
        val renamed = material.listedVariants.filter { (id, name) -> id != null && existing[id] != name }
        // Names are unique per material, so with several renames (e.g. swapped names) they are cleared first.
        if (renamed.size > 1) {
            MaterialVariants.update({ MaterialVariants.id inList renamed.map { it.first!! } }) {
                it[MaterialVariants.name] = null
            }
        }
        renamed.forEach { (id, name) ->
            MaterialVariants.update({ MaterialVariants.id eq id!! }) { it[MaterialVariants.name] = name }
        }
        if (material is NewBulkMaterial) material.variants.forEach { variant ->
            variant.id?.let { id -> BulkStock.update({ BulkStock.variantId eq id }) { it.fillStock(variant) } }
        }
    }

    /** Creates the listed variants without an id. */
    private fun insertVariants(materialId: EntityID<Uuid>, material: NewMaterial) {
        fun insertVariant(name: String?) = MaterialVariants.insertAndGetId {
            it[MaterialVariants.materialId] = materialId
            it[MaterialVariants.trackingType] = material.trackingType
            it[MaterialVariants.name] = name
        }
        when (material) {
            is NewBulkMaterial -> material.variants.filter { it.id == null }.forEach { variant ->
                val variantId = insertVariant(variant.name)
                BulkStock.insert {
                    it[BulkStock.variantId] = variantId
                    it.fillStock(variant)
                }
            }

            is NewIndividualMaterial -> material.variants.filter { it.id == null }.forEach { insertVariant(it.name) }
        }
    }

    private fun find(id: Uuid): Material = findWhere { Materials.id eq id }.singleOrNull() ?: notFound("Dieses Material")

    private fun findWhere(predicate: () -> Op<Boolean>): List<Material> {
        val materialRows = Materials.selectAll().where(predicate).orderBy(Materials.name).toList()
        val variantsByMaterial = MaterialVariants
            .leftJoin(BulkStock)
            .leftJoin(StorageLocations, { BulkStock.storageLocationId }, { StorageLocations.id })
            .selectAll()
            .where { MaterialVariants.materialId inList materialRows.map { it[Materials.id] } }
            .groupBy { it[MaterialVariants.materialId].value }
            .mapValues { (_, rows) -> rows.sortedWith(compareBy(variantNameOrder) { it[MaterialVariants.name] }) }
        val itemCounts = individualItemCounts()
        val loaned = loanedCounts()
        return materialRows.map { row ->
            val id = row[Materials.id].value
            val variantRows = variantsByMaterial[id].orEmpty()
            when (row[Materials.trackingType]) {
                MaterialTrackingType.BULK -> {
                    val variants = variantRows.map { variant ->
                        val variantId = variant[MaterialVariants.id].value
                        BulkVariant(
                            id = variantId,
                            name = variant[MaterialVariants.name],
                            availableCount = variant[BulkStock.quantityAvailable],
                            damagedCount = variant[BulkStock.quantityDamaged],
                            loanedCount = loaned[variantId] ?: 0,
                            location = LocationService.toStorageLocationOrNull(variant),
                            locationDetail = variant[BulkStock.locationDetail]
                        )
                    }
                    BulkMaterial(
                        id = id,
                        name = row[Materials.name],
                        category = row[Materials.category],
                        description = row[Materials.description],
                        archivedAt = row[Materials.archivedAt],
                        availableCount = variants.sumOf { it.availableCount },
                        damagedCount = variants.sumOf { it.damagedCount },
                        loanedCount = variants.sumOf { it.loanedCount },
                        variants = variants
                    )
                }

                MaterialTrackingType.INDIVIDUAL -> {
                    val variants = variantRows.map { variant ->
                        val variantId = variant[MaterialVariants.id].value
                        val counts = itemCounts[variantId] ?: Counts()
                        IndividualVariant(
                            id = variantId,
                            name = variant[MaterialVariants.name],
                            availableCount = counts.available,
                            damagedCount = counts.damaged,
                            loanedCount = loaned[variantId] ?: 0
                        )
                    }
                    IndividualMaterial(
                        id = id,
                        name = row[Materials.name],
                        category = row[Materials.category],
                        description = row[Materials.description],
                        archivedAt = row[Materials.archivedAt],
                        availableCount = variants.sumOf { it.availableCount },
                        damagedCount = variants.sumOf { it.damagedCount },
                        loanedCount = variants.sumOf { it.loanedCount },
                        variants = variants
                    )
                }
            }
        }
    }

    /** Availability rules for individual items, per variant. LOST and DECOMMISSIONED items count as neither. */
    private fun individualItemCounts(): Map<Uuid, Counts> {
        val isActive = IndividualItems.status eq ItemStatus.ACTIVE
        val isPoor = IndividualItems.condition eq ItemCondition.POOR
        val available = countWhere(isAvailableItem)
        val damaged = countWhere((isActive and isPoor) or (IndividualItems.status eq ItemStatus.IN_REPAIR))
        return IndividualItems
            .select(IndividualItems.variantId, available, damaged)
            .groupBy(IndividualItems.variantId)
            .associate { it[IndividualItems.variantId].value to Counts(it[available] ?: 0, it[damaged] ?: 0) }
    }

    private fun countWhere(condition: Op<Boolean>) = Case().When(condition, intLiteral(1)).Else(intLiteral(0)).sum()

    private val Material.trackingType
        get() = when (this) {
            is BulkMaterial -> MaterialTrackingType.BULK
            is IndividualMaterial -> MaterialTrackingType.INDIVIDUAL
        }

    private val NewMaterial.trackingType
        get() = when (this) {
            is NewBulkMaterial -> MaterialTrackingType.BULK
            is NewIndividualMaterial -> MaterialTrackingType.INDIVIDUAL
        }

    /** The listed variants as id (null for new ones) and name. */
    private val NewMaterial.listedVariants: List<Pair<Uuid?, String?>>
        get() = when (this) {
            is NewBulkMaterial -> variants.map { it.id to it.name }
            is NewIndividualMaterial -> variants.map { it.id to it.name }
        }

    private fun UpdateBuilder<*>.fillMaterial(material: NewMaterial) {
        this[Materials.name] = material.name
        // Trimmed on receive; a blank category means none.
        this[Materials.category] = material.category?.ifEmpty { null }
        this[Materials.description] = material.description
    }

    private fun UpdateBuilder<*>.fillStock(variant: NewBulkVariant) {
        this[BulkStock.quantityAvailable] = variant.availableCount
        this[BulkStock.quantityDamaged] = variant.damagedCount
        this[BulkStock.storageLocationId] = variant.storageLocationId
        this[BulkStock.locationDetail] = variant.locationDetail
    }
}
