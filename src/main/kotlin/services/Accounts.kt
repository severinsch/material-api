package services

import api.LoginMethod
import api.SessionToken
import api.SetupLink
import api.TenantRole
import auth.Tokens
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

// Sessions, setup links and the rules for changing accounts, shared by the services that manage accounts. Must be
// called inside a transaction.

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

internal fun requireFreeUsername(username: String, exceptUser: Uuid? = null) {
    val taken = PasswordLogins.select(PasswordLogins.userId)
        .where { (PasswordLogins.username eq username) and (exceptUser?.let { PasswordLogins.userId neq it } ?: Op.TRUE) }
        .any()
    if (taken) conflict("Den Benutzernamen $username gibt es schon.")
}

/**
 * Keeps at least one enabled admin in [tenant] when [userId] stops being one (demoted, removed, disabled, deleted).
 * Locks the tenant first, so two admins demoting each other at the same time can't both succeed. A tenant without
 * admins (e.g. a new one) stays possible.
 */
internal fun requireOtherTenantAdmin(tenant: Uuid, userId: Uuid) {
    val name = Tenants.select(Tenants.name).where { Tenants.id eq tenant }.forUpdate().single()[Tenants.name]
    val enabledAdmins = TenantMembers.innerJoin(Users)
        .select(Users.id)
        .where { (TenantMembers.tenantId eq tenant) and (TenantMembers.role eq TenantRole.ADMIN) and Users.disabledAt.isNull() }
        .map { it[Users.id].value }
    if (enabledAdmins == listOf(userId)) {
        conflict("$name braucht mindestens einen Admin. Mach zuerst jemand anderen zum Admin.")
    }
}

/** Like [requireOtherTenantAdmin] for platform admins, of which there must always be one. */
internal fun requireOtherPlatformAdmin(userId: Uuid) {
    val enabledAdmins = Users.select(Users.id)
        .where { (Users.isPlatformAdmin eq true) and Users.disabledAt.isNull() }
        .orderBy(Users.id)
        .forUpdate()
        .map { it[Users.id].value }
    if (enabledAdmins == listOf(userId)) conflict("Es muss mindestens einen Plattform-Admin geben.")
}

/** Checks the rules for disabling or deleting [userId]: someone else must remain admin wherever they are one. */
internal fun requireOtherAdmins(userId: Uuid) {
    requireOtherPlatformAdmin(userId)
    TenantMembers.select(TenantMembers.tenantId)
        .where { (TenantMembers.userId eq userId) and (TenantMembers.role eq TenantRole.ADMIN) }
        .orderBy(TenantMembers.tenantId) // Lock order, against deadlocks.
        .map { it[TenantMembers.tenantId].value }
        .forEach { requireOtherTenantAdmin(it, userId) }
}
