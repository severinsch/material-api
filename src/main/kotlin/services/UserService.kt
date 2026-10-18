package services

import api.LoginMethod
import api.Membership
import api.SetupLink
import api.User
import api.UserUpdate
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** All accounts, for platform admins. Accounts are created as members of a tenant (MemberService). */
class UserService(private val db: Db) {

    suspend fun getAll(): List<User> = db.query { findWhere { Op.TRUE } }

    /** Disabling ends the user's sessions. Someone else must remain admin wherever the user stops being one. */
    suspend fun update(userId: Uuid, update: UserUpdate): User = db.query {
        lockUser(userId)
        val user = find(userId)
        val disables = update.isDisabled && !user.isDisabled
        if (disables) requireOtherAdmins(userId)
        if (user.isPlatformAdmin && !update.isPlatformAdmin) requireOtherPlatformAdmin(userId)
        Users.update({ Users.id eq userId }) {
            it[displayName] = update.displayName
            it[isPlatformAdmin] = update.isPlatformAdmin
            if (update.isDisabled != user.isDisabled) it[disabledAt] = if (update.isDisabled) Clock.System.now() else null
        }
        update.username?.takeIf { it != user.username }?.let { username ->
            if (user.username == null) invalid("Dieses Konto hat keinen Benutzernamen.")
            requireFreeUsername(username, exceptUser = userId)
            PasswordLogins.update({ PasswordLogins.userId eq userId }) { it[PasswordLogins.username] = username }
        }
        if (disables) Sessions.deleteWhere { Sessions.userId eq userId }
        if (update.isPlatformAdmin != user.isPlatformAdmin || update.isDisabled != user.isDisabled) {
            revokeSetupLink(userId)
        }
        find(userId)
    }

    /** Deletes the account with its memberships and sessions. Nothing else references users. */
    suspend fun delete(userId: Uuid) = db.query {
        if (!lockUser(userId)) notFound("Benutzer", userId)
        requireOtherAdmins(userId)
        Users.deleteWhere { id eq userId }
    }

    /** A new setup link for an account with a password login. Replaces an older link. */
    suspend fun setupLink(userId: Uuid): SetupLink = db.query {
        lockUser(userId)
        val user = find(userId)
        if (user.username == null) invalid("Dieses Konto meldet sich nicht mit Passwort an.")
        if (user.isDisabled) conflict("Das Konto ist deaktiviert.")
        issueSetupLink(userId, if (user.setupPending) NEW_ACCOUNT_LINK_VALIDITY else RESET_LINK_VALIDITY)
    }

    private fun find(id: Uuid): User = findWhere { Users.id eq id }.singleOrNull() ?: notFound("Benutzer", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<User> {
        val userRows = Users.leftJoin(PasswordLogins)
            .select(Users.columns + PasswordLogins.columns)
            .where(predicate)
            .orderBy(Users.displayName)
            .toList()
        val memberships = TenantMembers.select(TenantMembers.columns)
            .where { TenantMembers.userId inList userRows.map { it[Users.id] } }
            .orderBy(TenantMembers.tenantId)
            .groupBy({ it[TenantMembers.userId].value }) { Membership(it[TenantMembers.tenantId].value, it[TenantMembers.role]) }
        return userRows.map { row ->
            val id = row[Users.id].value
            val username = row.getOrNull(PasswordLogins.username)
            User(
                id = id,
                displayName = row[Users.displayName],
                username = username,
                // The only login method so far.
                loginMethod = LoginMethod.PASSWORD,
                isPlatformAdmin = row[Users.isPlatformAdmin],
                isDisabled = row[Users.disabledAt] != null,
                setupPending = username != null && row.getOrNull(PasswordLogins.passwordHash) == null,
                memberships = memberships[id].orEmpty()
            )
        }
    }
}
