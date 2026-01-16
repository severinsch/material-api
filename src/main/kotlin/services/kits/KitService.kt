package services.kits


import database.*
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import parseUUID
import routes.*
import java.util.*


class KitService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<Kit> = transaction {
        val kitRows = Kits
            .selectAll()
            .where(predicate)

        return@transaction kitRows.map { row ->
            val fixedMaterials = (KitFixedMaterials innerJoin Materials)
                .selectAll()
                .where { KitFixedMaterials.kitId eq row[Kits.id] }
                .map {
                    FixedMaterial(
                        materialId = it[Materials.id].toString(),
                        quantity = it[KitFixedMaterials.quantityRequired]
                    )
                }
            val subResults = (KitSubstitutionGroups innerJoin KitSubstitutionOptions innerJoin Materials)
                .selectAll()
                .where { KitSubstitutionGroups.kitId eq row[Kits.id] }
                .groupBy { it[KitSubstitutionOptions.groupId].value }
                .map { (groupId, rows) ->
                    val firstRow = rows.first()
                    SubstitutionGroup(
                        id = groupId.toString(),
                        name = firstRow[KitSubstitutionGroups.name],
                        isOptional = firstRow[KitSubstitutionGroups.isOptional],
                        requiredUnits = firstRow[KitSubstitutionGroups.requiredUnits].toDouble(),
                        minRequiredUnits = firstRow[KitSubstitutionGroups.minRequiredUnits].toDouble(),
                        options = rows.map {
                            SubstitutionOption(
                                materialId = it[Materials.id].value.toString(),
                                unitValue = it[KitSubstitutionOptions.unitValue].toDouble(),
                                isPreferred = it[KitSubstitutionOptions.isPreferred]
                            )
                        }
                    )

                }
            return@map Kit(
                id = row[Kits.id].toString(),
                name = row[Kits.name],
                description = row[Kits.description],
                fixedMaterials = fixedMaterials,
                substitutionGroups = subResults
            )
        }
    }

    fun getById(id: UUID): Kit? {
        return getWithCondition { Kits.id eq id }.singleOrNull()
    }

    fun getAll(): List<Kit> {
        return getWithCondition { Op.TRUE }
    }

    fun create(newKit: NewKit): Kit = transaction {
        val newId = Kits.insertAndGetId {
            it[name] = newKit.name
            it[description] = newKit.description
        }

        newKit.fixedMaterials.forEach { fixedMaterial ->
            KitFixedMaterials.insert {
                it[kitId] = newId.value
                it[materialId] = parseUUID(fixedMaterial.materialId)
                it[quantityRequired] = fixedMaterial.quantity
            }
        }

        newKit.substitutionGroups.forEach { group ->
            val newGroupId = KitSubstitutionGroups.insertAndGetId {
                it[kitId] = newId.value
                it[name] = group.name
                it[isOptional] = group.isOptional
                it[requiredUnits] = group.requiredUnits.toBigDecimal()
                it[minRequiredUnits] = group.minRequiredUnits.toBigDecimal()
            }
            group.options.forEach { option ->
                KitSubstitutionOptions.insert {
                    it[groupId] = newGroupId.value
                    it[materialId] = parseUUID(option.materialId)
                    it[unitValue] = option.unitValue.toBigDecimal()
                    it[isPreferred] = option.isPreferred
                }
            }
        }

        return@transaction getById(newId.value) ?: throw Exception(
            "Failed to create kit with name '${newKit.name}'"
        )
    }
}