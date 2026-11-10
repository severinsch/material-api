package services

import api.ErrorCode
import kotlin.time.Duration

/** The request is invalid, e.g. it references a material that doesn't exist. Mapped to 400. */
class InvalidRequestException(message: String) : RuntimeException(message)

/** Wrong credentials when logging in. Mapped to 401; otherwise 401 only means that there is no valid session. */
class UnauthorizedException(message: String) : RuntimeException(message)

/** The user isn't allowed to do this. Mapped to 403. */
class ForbiddenException(message: String) : RuntimeException(message)

/** The requested resource doesn't exist. Mapped to 404. */
class NotFoundException(message: String) : RuntimeException(message)

/**
 * The request conflicts with the current state, e.g. an item that is already on loan. Mapped to 409, with [code] for
 * conflicts that clients handle specifically.
 */
class ConflictException(message: String, val code: ErrorCode? = null) : RuntimeException(message)

/** Too many attempts, e.g. failed logins; the client may try again after [retryAfter]. Mapped to 429. */
class TooManyRequestsException(message: String, val retryAfter: Duration) : RuntimeException(message)

/** The server is busy, e.g. too many logins at once. Mapped to 503. */
class BusyException(message: String) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw InvalidRequestException(message)

internal fun forbidden(message: String): Nothing = throw ForbiddenException(message)

/**
 * [what] is the missing resource as accusative object, e.g. "Dieses Material" or "Diesen Gegenstand". Clients only
 * use ids they got from the API, so the likely cause is a page that is out of date, e.g. after someone else deleted
 * the resource.
 */
internal fun notFound(what: String): Nothing = throw NotFoundException("$what gibt es nicht mehr. $RELOAD")

internal fun conflict(message: String, code: ErrorCode? = null): Nothing = throw ConflictException(message, code)

/** The usual advice when a request refers to something that changed meanwhile. */
internal const val RELOAD = "Lade die Seite neu."
