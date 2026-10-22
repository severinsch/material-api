package services

import api.*
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

/** Materials are written together with their complete list of variants, see [NewMaterial]. */
class MaterialService(private val db: Db) {

    private companion object {
        const val MATERIAL_CHANGED = "Das Material wurde inzwischen geändert. Lade die Seite neu."
        const val MATERIALS_CHANGED = "Die Materialien wurden inzwischen geändert. Lade die Seite neu."
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

    /** Replaces a material including its variants. Its type can't be changed. */
    suspend fun update(tenant: TenantId, id: Uuid, material: NewMaterial): Material = db.tenantQuery(tenant) {
        if (find(id).trackingType != material.trackingType) conflict("Die Erfassungsart eines Materials kann nicht geändert werden.")
        val existing = lockVariantsOf(listOf(id)).associate { it.id to it.name }
        val listed = material.listedVariants.mapNotNull { it.first }
        val unknown = (listed + material.removedVariantIds).filter { it !in existing }
        if (unknown.isNotEmpty()) {
            existingVariants(unknown).firstOrNull()?.let { invalid("Die Variante $it gehört nicht zu diesem Material.") }
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

    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        if (Materials.deleteWhere { Materials.id eq id } == 0) notFound("Material", id)
    }

    /**
     * Merges materials into the first one, see [MaterialMerge]. Everything that references a variant comes along
     * unchanged; kit options for a specific variant follow it through their cascading key.
     */
    suspend fun merge(tenant: TenantId, merge: MaterialMerge): Material = db.tenantQuery(tenant) {
        val targetId = merge.materialIds.first()
        val sourceIds = merge.materialIds.drop(1)

        // Variants first, in the same order as everywhere else. Then the materials, so nobody can add variants or
        // kit options to them meanwhile; their variants are read again, including any added just before.
        lockVariantsOf(merge.materialIds)
        val materials = Materials.select(Materials.id, Materials.name, Materials.trackingType)
            .where { Materials.id inList merge.materialIds }
            .orderBy(Materials.id)
            .forUpdate()
            .associate { it[Materials.id].value to (it[Materials.name] to it[Materials.trackingType]) }
        // The client lists materials it has seen, so a missing one was most likely deleted or merged meanwhile.
        if (merge.materialIds.any { it !in materials }) conflict(MATERIALS_CHANGED)
        if (materials.values.map { it.second }.distinct().size > 1) {
            invalid("Nur Materialien derselben Erfassungsart können zusammengeführt werden.")
        }
        val variants = lockVariantsOf(merge.materialIds)

        val unknown = merge.variantNames.keys.filter { id -> variants.none { it.id == id } }
        if (unknown.isNotEmpty()) {
            existingVariants(unknown).firstOrNull()?.let {
                invalid("Die Variante $it gehört zu keinem der zusammengeführten Materialien.")
            }
            conflict(MATERIALS_CHANGED)
        }
        val names = variants.associate { it.id to (merge.variantNames[it.id] ?: it.name) }
        if (names.values.any { it == null }) invalid("Hat ein Material mehrere Varianten, braucht jede einen Namen.")
        if (names.values.toSet().size < names.size) invalid("Die Varianten eines Materials brauchen unterschiedliche Namen.")

        val singleVariants = variants.groupBy { it.materialId }.filterValues { it.size == 1 }.mapValues { it.value.single().id }
        requireKitsAfterMerge(targetId, sourceIds, materials.mapValues { it.value.first }, singleVariants.keys)

        // "Any variant" of a material with a single variant meant exactly that one, and must not mean all variants
        // of the merged material afterwards.
        singleVariants.forEach { (materialId, variantId) ->
            KitGroupOptions.update({ (KitGroupOptions.materialId eq materialId) and KitGroupOptions.variantId.isNull() }) {
                it[KitGroupOptions.variantId] = variantId
            }
        }
        // Names are unique per material and may be swapped or taken over, so they are cleared first.
        val variantIds = variants.map { it.id }
        MaterialVariants.update({ MaterialVariants.id inList variantIds }) { it[MaterialVariants.name] = null }
        MaterialVariants.update({ MaterialVariants.materialId inList sourceIds }) {
            it[MaterialVariants.materialId] = EntityID(targetId, Materials)
        }
        Materials.deleteWhere { Materials.id inList sourceIds }
        Materials.update({ Materials.id eq targetId }) { it[Materials.name] = merge.name }
        names.forEach { (id, name) ->
            MaterialVariants.update({ MaterialVariants.id eq id }) { it[MaterialVariants.name] = name }
        }
        find(targetId)
    }

    /**
     * Rejects merges that would change what kits mean: "any variant" of a source with several variants (except
     * [singleVariantMaterials]) can't be expressed afterwards, and "any variant" of the target in a group that also
     * lists a source would overlap with it. [materialNames] are the current names.
     */
    private fun requireKitsAfterMerge(
        targetId: Uuid,
        sourceIds: List<Uuid>,
        materialNames: Map<Uuid, String>,
        singleVariantMaterials: Set<Uuid>
    ) {
        val options = KitGroupOptions.innerJoin(KitGroups).innerJoin(Kits)
            .select(KitGroupOptions.groupId, KitGroupOptions.materialId, KitGroupOptions.variantId, Kits.name)
            .where { KitGroupOptions.materialId inList listOf(targetId) + sourceIds }
            .toList()
        fun Iterable<ResultRow>.kitNames() = map { it[Kits.name] }.distinct().sorted()
        val anyVariant = options.filter { it[KitGroupOptions.variantId] == null }

        for (sourceId in sourceIds.filter { it !in singleVariantMaterials }) {
            val kits = anyVariant.filter { it[KitGroupOptions.materialId].value == sourceId }.kitNames()
            if (kits.isNotEmpty()) {
                conflict(
                    "${materialNames.getValue(sourceId)} wird ${inKits(kits)} mit beliebiger Variante verwendet, was " +
                        "sich nach dem Zusammenführen nicht mehr ausdrücken lässt. Wähle dort zuerst bestimmte " +
                        "Varianten aus, oder führe die anderen Materialien in dieses zusammen."
                )
            }
        }
        if (targetId !in singleVariantMaterials) {
            val groups = anyVariant.filter { it[KitGroupOptions.materialId].value == targetId }
                .map { it[KitGroupOptions.groupId] }.toSet()
            val kits = options
                .filter { it[KitGroupOptions.groupId] in groups && it[KitGroupOptions.materialId].value != targetId }
                .kitNames()
            if (kits.isNotEmpty()) {
                conflict(
                    "${materialNames.getValue(targetId)} wird ${inKits(kits)} mit beliebiger Variante verwendet, " +
                        "zusammen mit einem der anderen Materialien im selben Teil. Danach würden sich die " +
                        "Materialien des Teils überschneiden. Entferne zuerst eines davon aus dem Teil."
                )
            }
        }
    }

    /** Locks the variants of materials (in the same order as [lockVariants]) and returns them. */
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
            val name = displayName(material.name, variant.name, material.variants.size)
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

    private fun find(id: Uuid): Material = findWhere { Materials.id eq id }.singleOrNull() ?: notFound("Material", id)

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
        this[Materials.category] = material.category
        this[Materials.description] = material.description
    }

    private fun UpdateBuilder<*>.fillStock(variant: NewBulkVariant) {
        this[BulkStock.quantityAvailable] = variant.availableCount
        this[BulkStock.quantityDamaged] = variant.damagedCount
        this[BulkStock.storageLocationId] = variant.storageLocationId
        this[BulkStock.locationDetail] = variant.locationDetail
    }
}
