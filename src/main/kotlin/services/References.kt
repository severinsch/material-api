package services

import api.MaterialTrackingType
import database.IndividualItems
import database.Materials
import database.StorageLocations
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// Checks for ids referenced in request bodies. They give readable 400 errors for the common mistakes; the foreign
// keys in the schema still guarantee consistency. Must be called inside a transaction.

internal fun requireMaterial(id: Uuid, type: MaterialTrackingType) {
    val row = Materials.select(Materials.name, Materials.trackingType).where { Materials.id eq id }.singleOrNull()
        ?: invalid("Material $id gibt es nicht.")
    if (row[Materials.trackingType] != type) {
        val expected = when (type) {
            MaterialTrackingType.BULK -> "nach Menge"
            MaterialTrackingType.INDIVIDUAL -> "einzeln"
        }
        invalid("${row[Materials.name]} wird nicht $expected erfasst.")
    }
}

internal fun requireMaterials(ids: Collection<Uuid>) {
    val existing = Materials.select(Materials.id).where { Materials.id inList ids }.map { it[Materials.id].value }
    (ids - existing.toSet()).firstOrNull()?.let { invalid("Material $it gibt es nicht.") }
}

internal fun requireItem(id: Uuid) {
    if (IndividualItems.select(IndividualItems.id).where { IndividualItems.id eq id }.empty()) {
        invalid("Gegenstand $id gibt es nicht.")
    }
}

internal fun requireLocation(id: Uuid?) {
    if (id != null && StorageLocations.select(StorageLocations.id).where { StorageLocations.id eq id }.empty()) {
        invalid("Lagerort $id gibt es nicht.")
    }
}

/** Rejects changing the parent of a resource on update, e.g. moving a damage report to another item. */
internal fun requireUnchanged(field: String, old: Uuid, new: Uuid) {
    if (old != new) invalid("$field kann nicht geändert werden.")
}
