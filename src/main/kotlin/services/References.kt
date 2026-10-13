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
    val actual = Materials.select(Materials.trackingType).where { Materials.id eq id }.singleOrNull()
        ?.get(Materials.trackingType)
        ?: invalid("Material $id doesn't exist")
    if (actual != type) invalid("Material $id has tracking type $actual, expected $type")
}

internal fun requireMaterials(ids: Collection<Uuid>) {
    val existing = Materials.select(Materials.id).where { Materials.id inList ids }.map { it[Materials.id].value }
    (ids - existing.toSet()).firstOrNull()?.let { invalid("Material $it doesn't exist") }
}

internal fun requireItem(id: Uuid) {
    if (IndividualItems.select(IndividualItems.id).where { IndividualItems.id eq id }.empty()) {
        invalid("Item $id doesn't exist")
    }
}

internal fun requireLocation(id: Uuid?) {
    if (id != null && StorageLocations.select(StorageLocations.id).where { StorageLocations.id eq id }.empty()) {
        invalid("Location $id doesn't exist")
    }
}

/** Rejects changing the parent of a resource on update, e.g. moving a damage report to another item. */
internal fun requireUnchanged(field: String, old: Uuid, new: Uuid) {
    if (old != new) invalid("$field can't be changed")
}
