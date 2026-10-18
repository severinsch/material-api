package services

import api.*
import auth.LoginThrottle
import auth.Passwords
import auth.Tokens
import auth.UserPrincipal
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Logging in and out, the current user's own account, and setup links. */
class AuthService(private val db: Db, private val passwords: Passwords, private val throttle: LoginThrottle) {

    /** Unknown users, users with a pending setup and disabled users get the same answer as a wrong password. */
    suspend fun login(credentials: Credentials, clientIp: String): SessionToken {
        val username = credentials.username.trim().lowercase()
        throttle.check(username, clientIp)
        val account = db.query {
            PasswordLogins.innerJoin(Users)
                .select(PasswordLogins.userId, PasswordLogins.passwordHash)
                .where { (PasswordLogins.username eq username) and Users.disabledAt.isNull() }
                .singleOrNull()
        }
        if (!passwords.verify(credentials.password, account?.get(PasswordLogins.passwordHash))) {
            throttle.failed(username, clientIp)
            throw UnauthorizedException("Benutzername oder Passwort ist falsch.")
        }
        throttle.succeeded(username, clientIp)
        val userId = checkNotNull(account)[PasswordLogins.userId].value
        return db.query { startSession(userId, LoginMethod.PASSWORD) }
    }

    /** The user of a valid session, or null. Records the use at most once per hour, so most requests only read. */
    suspend fun authenticate(token: String): UserPrincipal? = db.query {
        val now = Clock.System.now()
        val row = Sessions.innerJoin(Users)
            .select(Sessions.id, Sessions.userId, Sessions.lastUsedAt, Users.displayName, Users.isPlatformAdmin)
            .where {
                (Sessions.tokenHash eq Tokens.hash(token)) and (Sessions.expiresAt greater now) and
                    (Sessions.lastUsedAt greater now - SESSION_IDLE_LIMIT) and Users.disabledAt.isNull()
            }
            .singleOrNull() ?: return@query null
        if (row[Sessions.lastUsedAt] < now - 1.hours) {
            Sessions.update({ Sessions.id eq row[Sessions.id] }) { it[lastUsedAt] = now }
        }
        UserPrincipal(
            userId = row[Sessions.userId].value,
            sessionId = row[Sessions.id].value,
            displayName = row[Users.displayName],
            isPlatformAdmin = row[Users.isPlatformAdmin]
        )
    }

    suspend fun logout(user: UserPrincipal) = db.query {
        Sessions.deleteWhere { id eq user.sessionId }
    }

    suspend fun me(user: UserPrincipal): CurrentUser = db.query {
        val roles = TenantMembers.select(TenantMembers.tenantId, TenantMembers.role)
            .where { TenantMembers.userId eq user.userId }
            .associate { it[TenantMembers.tenantId].value to it[TenantMembers.role] }
        val tenants = Tenants.selectAll()
            .where { Tenants.deletedAt.isNull() and (if (user.isPlatformAdmin) Op.TRUE else Tenants.id inList roles.keys) }
            .orderBy(Tenants.name)
            .map {
                val id = it[Tenants.id].value
                UserTenant(
                    id = id,
                    name = it[Tenants.name],
                    slug = it[Tenants.slug],
                    role = if (user.isPlatformAdmin) TenantRole.ADMIN else roles.getValue(id),
                    isMember = id in roles
                )
            }
        CurrentUser(
            id = user.userId,
            displayName = user.displayName,
            username = PasswordLogins.select(PasswordLogins.username)
                .where { PasswordLogins.userId eq user.userId }
                .singleOrNull()?.get(PasswordLogins.username),
            isPlatformAdmin = user.isPlatformAdmin,
            tenants = tenants
        )
    }

    suspend fun updateProfile(user: UserPrincipal, profile: NewProfile): CurrentUser {
        db.query { Users.update({ Users.id eq user.userId }) { it[displayName] = profile.displayName } }
        return me(user.copy(displayName = profile.displayName))
    }

    /** Throttled like logins: knowing the password is worth more than the session. Ends all other sessions. */
    suspend fun changePassword(user: UserPrincipal, change: PasswordChange, clientIp: String) {
        val login = db.query {
            PasswordLogins.select(PasswordLogins.username, PasswordLogins.passwordHash)
                .where { PasswordLogins.userId eq user.userId }
                .singleOrNull()
        } ?: invalid("Dein Konto hat kein Passwort.")
        val username = login[PasswordLogins.username]
        throttle.check(username, clientIp)
        if (!passwords.verify(change.currentPassword, login[PasswordLogins.passwordHash])) {
            throttle.failed(username, clientIp)
            invalid("Das aktuelle Passwort ist falsch.")
        }
        throttle.succeeded(username, clientIp)
        val hash = passwords.hash(change.newPassword)
        db.query {
            PasswordLogins.update({ PasswordLogins.userId eq user.userId }) { it[passwordHash] = hash }
            Sessions.deleteWhere { (userId eq user.userId) and (id neq user.sessionId) }
            revokeSetupLink(user.userId)
        }
    }

    suspend fun inspectSetup(setup: SetupToken): SetupInfo = db.query { setupInfo(setup.token) } ?: invalidLink()

    /** Uses up the link, sets the password, ends all existing sessions of the user and starts a new one. */
    suspend fun completeSetup(setup: PasswordSetup): SessionToken {
        // Checked before hashing, so invalid links can't keep the hashing slots busy.
        db.query { setupInfo(setup.token) } ?: invalidLink()
        val hash = passwords.hash(setup.password)
        return db.query {
            // One statement, so a link can't be used twice concurrently.
            val userId = SetupTokens
                .deleteReturning(listOf(SetupTokens.userId)) {
                    (SetupTokens.tokenHash eq Tokens.hash(setup.token)) and (SetupTokens.expiresAt greater Clock.System.now())
                }
                .singleOrNull()?.get(SetupTokens.userId)?.value
                ?.takeIf { Users.select(Users.id).where { (Users.id eq it) and Users.disabledAt.isNull() }.any() }
                ?: invalidLink()
            PasswordLogins.update({ PasswordLogins.userId eq userId }) { it[passwordHash] = hash }
            Sessions.deleteWhere { Sessions.userId eq userId }
            startSession(userId, LoginMethod.PASSWORD)
        }
    }

    /** Deletes expired sessions and setup links, and forgets old failed logins. */
    suspend fun cleanUp() {
        db.query {
            val now = Clock.System.now()
            Sessions.deleteWhere { (expiresAt lessEq now) or (lastUsedAt lessEq now - SESSION_IDLE_LIMIT) }
            SetupTokens.deleteWhere { expiresAt lessEq now }
        }
        throttle.cleanUp()
    }

    /** The account of a valid setup link of an enabled user. */
    private fun setupInfo(token: String): SetupInfo? = SetupTokens.innerJoin(Users).innerJoin(PasswordLogins)
        .select(Users.displayName, PasswordLogins.username)
        .where {
            (SetupTokens.tokenHash eq Tokens.hash(token)) and (SetupTokens.expiresAt greater Clock.System.now()) and
                Users.disabledAt.isNull()
        }
        .singleOrNull()
        ?.let { SetupInfo(it[Users.displayName], it[PasswordLogins.username]) }

    private fun invalidLink(): Nothing = throw NotFoundException("Der Link ist ungültig oder abgelaufen.")
}
