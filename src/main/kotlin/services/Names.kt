package services

import database.IndividualItems
import database.MaterialVariants
import database.Materials
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// Names of variants and items in messages shown to users, all built here so the format can change in one place. A
// variant's name is only included if its material has several variants, since clients don't show variants otherwise:
// "Seil 15 m" and "Kohtenplane 45 cm Latz K45 01", but "Hering" and "Kohtenblatt 3". Must be called inside a
// transaction.

/** E.g. "Seil 15 m"; just the material's name if it has a single variant ([variantCount]). */
internal fun displayName(materialName: String, variantName: String?, variantCount: Int): String =
    if (variantCount > 1 && variantName != null) "$materialName $variantName" else materialName

/** Display names of the variants [ids], see [displayName]. */
internal fun variantNames(ids: Collection<Uuid>): Map<Uuid, String> {
    val rows = MaterialVariants.innerJoin(Materials)
        .select(MaterialVariants.id, MaterialVariants.materialId, MaterialVariants.name, Materials.name)
        .where { MaterialVariants.id inList ids }
        .toList()
    val variants = MaterialVariants.id.count()
    val variantCounts = MaterialVariants.select(MaterialVariants.materialId, variants)
        .where { MaterialVariants.materialId inList rows.map { it[MaterialVariants.materialId] }.distinct() }
        .groupBy(MaterialVariants.materialId)
        .associate { it[MaterialVariants.materialId].value to it[variants].toInt() }
    return rows.associate { row ->
        val variantCount = variantCounts.getValue(row[MaterialVariants.materialId].value)
        row[MaterialVariants.id].value to displayName(row[Materials.name], row[MaterialVariants.name], variantCount)
    }
}

internal fun variantName(id: Uuid): String = variantNames(listOf(id)).getValue(id)

/** E.g. "Kohtenblatt 3", or "Kohtenplane 45 cm Latz K45 01" if the material has several variants. */
internal fun itemName(id: Uuid): String {
    val row = IndividualItems.select(IndividualItems.variantId, IndividualItems.identifier)
        .where { IndividualItems.id eq id }
        .single()
    return "${variantName(row[IndividualItems.variantId].value)} ${row[IndividualItems.identifier]}"
}

/**
 * The order of variant names: numbers by value ("5 m" before "10 m"), text case-insensitively, the unnamed variant
 * first.
 */
internal val variantNameOrder: Comparator<String?> = nullsFirst(Comparator(::compareNatural))

private val chunks = Regex("[0-9]+|[^0-9]+")

private fun compareNatural(a: String, b: String): Int {
    val x = chunks.findAll(a).map { it.value }.toList()
    val y = chunks.findAll(b).map { it.value }.toList()
    for ((p, q) in x.zip(y)) {
        val result = if (p[0] in '0'..'9' && q[0] in '0'..'9') {
            val (m, n) = p.trimStart('0') to q.trimStart('0')
            compareValuesBy(m, n, { it.length }, { it })
        } else {
            p.compareTo(q, ignoreCase = true)
        }
        if (result != 0) return result
    }
    return compareValuesBy(x, y, { it.size }).takeIf { it != 0 } ?: a.compareTo(b)
}
