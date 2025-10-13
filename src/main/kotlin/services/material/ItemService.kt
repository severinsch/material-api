package services.material

import database.IndividualItems
import database.StorageLocations
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.selectAll
import routes.IndividualItemResponse
import routes.StorageLocation
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class ItemService {

    @OptIn(ExperimentalUuidApi::class)
    fun getAllOfType(typeId: Uuid): List<IndividualItemResponse> {
        val id = typeId.toJavaUuid()

        return IndividualItems
            .leftJoin(StorageLocations, { IndividualItems.storageLocationId }, { StorageLocations.id })
            .selectAll()
            .where { IndividualItems.materialId eq id }
            .map { row ->
                return@map IndividualItemResponse(
                    id = Uuid.parse(row[IndividualItems.id].toString()),
                    materialId = Uuid.parse(row[IndividualItems.materialId].toString()),
                    identifier = row[IndividualItems.identifier],
                    condition = row[IndividualItems.condition],
                    storageLocation = StorageLocation(
                        id = Uuid.parse(row[StorageLocations.id].toString()),
                        name = row[StorageLocations.name],
                        description = row[StorageLocations.description],
                    ),
                    purchasePrice = row[IndividualItems.purchasePrice]?.toDouble(),
                    purchaseDate = row[IndividualItems.purchaseDate]?.toString(),
                )
            }
    }
}