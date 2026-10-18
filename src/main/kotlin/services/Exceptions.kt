package services

import kotlin.time.Duration

/** The request is invalid, e.g. it references a material that doesn't exist. Mapped to 400. */
class InvalidRequestException(message: String) : RuntimeException(message)

/** Wrong credentials when logging in. Mapped to 401; otherwise 401 only means that there is no valid session. */
class UnauthorizedException(message: String) : RuntimeException(message)

/** The user isn't allowed to do this. Mapped to 403. */
class ForbiddenException(message: String) : RuntimeException(message)

/** The requested resource doesn't exist. Mapped to 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** The request conflicts with the current state, e.g. an item that is already on loan. Mapped to 409. */
class ConflictException(message: String) : RuntimeException(message)

/** Too many attempts, e.g. failed logins; the client may try again after [retryAfter]. Mapped to 429. */
class TooManyRequestsException(message: String, val retryAfter: Duration) : RuntimeException(message)

/** The server is busy, e.g. too many logins at once. Mapped to 503. */
class BusyException(message: String) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw InvalidRequestException(message)

internal fun forbidden(message: String): Nothing = throw ForbiddenException(message)

/** [resource] in German, e.g. "Material" or "Gegenstand". */
internal fun notFound(resource: String, id: Any): Nothing = throw NotFoundException("$resource $id wurde nicht gefunden.")

internal fun conflict(message: String): Nothing = throw ConflictException(message)
