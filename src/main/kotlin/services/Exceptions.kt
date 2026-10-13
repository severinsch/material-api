package services

/** The request is invalid, e.g. it references a material that doesn't exist. Mapped to 400. */
class InvalidRequestException(message: String) : RuntimeException(message)

/** The requested resource doesn't exist. Mapped to 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** The request conflicts with the current state, e.g. an item that is already on loan. Mapped to 409. */
class ConflictException(message: String) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw InvalidRequestException(message)

internal fun notFound(resource: String, id: Any): Nothing = throw NotFoundException("$resource $id not found")

internal fun conflict(message: String): Nothing = throw ConflictException(message)
