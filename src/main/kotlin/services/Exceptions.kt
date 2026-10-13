package services

/** The requested resource doesn't exist. Mapped to 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** The request conflicts with the current state, e.g. an item that is already on loan. Mapped to 409. */
class ConflictException(message: String) : RuntimeException(message)

internal fun notFound(resource: String, id: Any): Nothing = throw NotFoundException("$resource $id not found")
