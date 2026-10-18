package services

import api.LoginMethod
import api.SessionToken
import api.SetupLink
import auth.Tokens
import database.Sessions
import database.SetupTokens
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

// Sessions and setup links, shared by the services that manage accounts. Must be called inside a transaction.

/** Sessions end after this long without use… */
internal val SESSION_IDLE_LIMIT = 30.days

/** …and after this long at most. */
internal val SESSION_LIFETIME = 180.days

internal val NEW_ACCOUNT_LINK_VALIDITY = 7.days
internal val RESET_LINK_VALIDITY = 24.hours

internal fun startSession(userId: Uuid, method: LoginMethod): SessionToken {
    val token = Tokens.generate()
    val now = Clock.System.now()
    val session = SessionToken(token, now + SESSION_LIFETIME)
    Sessions.insert {
        it[tokenHash] = Tokens.hash(token)
        it[Sessions.userId] = userId
        it[loginMethod] = method
        it[lastUsedAt] = now
        it[expiresAt] = session.expiresAt
    }
    return session
}

/** Replaces the user's setup link, if any. The current password keeps working until the new link is used. */
internal fun issueSetupLink(userId: Uuid, validFor: Duration): SetupLink {
    val link = SetupLink(Tokens.generate(), Clock.System.now() + validFor)
    SetupTokens.upsert {
        it[SetupTokens.userId] = userId
        it[tokenHash] = Tokens.hash(link.token)
        it[expiresAt] = link.expiresAt
    }
    return link
}

/**
 * Invalidates the user's setup link. Called whenever their privileges change (memberships, platform admin flag,
 * disabled), so a link can never be used with more privileges than the user had when it was issued.
 */
internal fun revokeSetupLink(userId: Uuid) {
    SetupTokens.deleteWhere { SetupTokens.userId eq userId }
}
