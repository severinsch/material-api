package services

import api.MaterialTrackingType
import database.IndividualItems
import database.MaterialVariants
import database.Materials
import database.StorageLocations
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// Checks for ids referenced in request bodies. They give readable 400 errors for the common mistakes; the foreign
// keys in the schema still guarantee consistency. Must be called inside a transaction.
//
// Clients only send ids they got from the API, so an unknown id most likely belongs to something deleted (or merged)
// meanwhile, and the messages suggest reloading the page instead of naming the id.

private const val MATERIAL_GONE = "Ein ausgewähltes Material gibt es nicht mehr, oder es wurde geändert. $RELOAD"

/** Rejects unknown variants and variants of the wrong type. Returns the variant's material. */
internal fun requireVariant(id: Uuid, type: MaterialTrackingType): Uuid {
    val row = MaterialVariants.select(MaterialVariants.materialId, MaterialVariants.trackingType)
        .where { MaterialVariants.id eq id }
        .singleOrNull()
        ?: invalid(MATERIAL_GONE)
    if (row[MaterialVariants.trackingType] != type) {
        val expected = when (type) {
            MaterialTrackingType.BULK -> "nach Menge"
            MaterialTrackingType.INDIVIDUAL -> "einzeln"
        }
        invalid("${variantName(id)} wird nicht $expected erfasst.")
    }
    return row[MaterialVariants.materialId].value
}

/** Rejects unknown variants. Returns the material of each variant. */
internal fun requireVariants(ids: Collection<Uuid>): Map<Uuid, Uuid> {
    if (ids.isEmpty()) return emptyMap()
    val materials = MaterialVariants.select(MaterialVariants.id, MaterialVariants.materialId)
        .where { MaterialVariants.id inList ids }
        .associate { it[MaterialVariants.id].value to it[MaterialVariants.materialId].value }
    if (!materials.keys.containsAll(ids)) invalid(MATERIAL_GONE)
    return materials
}

internal fun requireMaterials(ids: Collection<Uuid>) {
    if (ids.isEmpty()) return
    val existing = Materials.select(Materials.id).where { Materials.id inList ids }.map { it[Materials.id].value }
    if (!existing.containsAll(ids)) invalid(MATERIAL_GONE)
}

internal fun requireItem(id: Uuid) {
    if (IndividualItems.select(IndividualItems.id).where { IndividualItems.id eq id }.empty()) {
        invalid("Diesen Gegenstand gibt es nicht mehr. $RELOAD")
    }
}

internal fun requireLocation(id: Uuid?) {
    if (id != null && StorageLocations.select(StorageLocations.id).where { StorageLocations.id eq id }.empty()) {
        invalid("Den ausgewählten Lagerort gibt es nicht mehr. $RELOAD")
    }
}

/** Rejects changing the parent of a resource on update, e.g. moving a damage report to another item. */
internal fun requireUnchanged(field: String, old: Uuid, new: Uuid) {
    if (old != new) invalid("$field kann nicht geändert werden.")
}
