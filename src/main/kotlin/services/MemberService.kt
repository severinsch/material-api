package services

import api.*
import auth.TenantAccess
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

/**
 * Members of a tenant, managed by its admins. Tenant admins can only change accounts that belong to their tenant
 * alone and aren't platform admins; otherwise an admin of one tenant could take over an account with access to
 * another. Platform admins can change everything and add users of other tenants.
 */
class MemberService(private val db: Db) {

    suspend fun getAll(tenant: TenantId): List<Member> = db.query {
        findWhere { TenantMembers.tenantId eq tenant.value }
    }

    /** Creates a new account and returns its setup link. */
    suspend fun create(access: TenantAccess, member: NewMember): CreatedMember = db.query {
        requireFreeUsername(member.username)
        val userId = Users.insertAndGetId { it[displayName] = member.displayName }
        PasswordLogins.insert {
            it[PasswordLogins.userId] = userId
            it[username] = member.username
        }
        TenantMembers.insert {
            it[tenantId] = access.tenant.value
            it[TenantMembers.userId] = userId
            it[role] = member.role
        }
        CreatedMember(find(access.tenant, userId.value), issueSetupLink(userId.value, NEW_ACCOUNT_LINK_VALIDITY))
    }

    /** Changes the role and the given account fields. Platform admins can also add users of other tenants this way. */
    suspend fun update(access: TenantAccess, userId: Uuid, update: MemberUpdate): Member = db.query {
        val tenant = access.tenant.value
        val current = findWhere { (TenantMembers.tenantId eq tenant) and (TenantMembers.userId eq userId) }.singleOrNull()
        val account = account(userId)
        if (account == null || (current == null && !access.user.isPlatformAdmin)) notFound("Mitglied", userId)
        requireNotPlatformAdmin(access, account)
        val renamed = update.displayName.changes(account.displayName) || update.username.changes(account.username)
        if (renamed) requireOnlyThisTenant(access, account)

        if (current == null) {
            TenantMembers.insert {
                it[tenantId] = tenant
                it[TenantMembers.userId] = userId
                it[role] = update.role
            }
        } else if (update.role != current.role) {
            if (current.role == TenantRole.ADMIN) requireOtherTenantAdmin(tenant, userId)
            TenantMembers.update({ (TenantMembers.tenantId eq tenant) and (TenantMembers.userId eq userId) }) {
                it[role] = update.role
            }
        }
        if (current?.role != update.role) revokeSetupLink(userId)
        update.displayName?.takeIf { it != account.displayName }?.let { name ->
            Users.update({ Users.id eq userId }) { it[displayName] = name }
        }
        update.username?.takeIf { it != account.username }?.let { username ->
            if (account.username == null) invalid("Dieses Konto hat keinen Benutzernamen.")
            requireFreeUsername(username, exceptUser = userId)
            PasswordLogins.update({ PasswordLogins.userId eq userId }) { it[PasswordLogins.username] = username }
        }
        find(access.tenant, userId)
    }

    /** Removes the membership. The account stays, even without memberships, until a platform admin deletes it. */
    suspend fun remove(access: TenantAccess, userId: Uuid) = db.query {
        val tenant = access.tenant.value
        val member = find(access.tenant, userId)
        requireNotPlatformAdmin(access, checkNotNull(account(userId)))
        if (member.role == TenantRole.ADMIN) requireOtherTenantAdmin(tenant, userId)
        TenantMembers.deleteWhere { (tenantId eq tenant) and (TenantMembers.userId eq userId) }
        revokeSetupLink(userId)
    }

    /** A new setup link, e.g. for a forgotten password. Replaces an older link; the password works until it's used. */
    suspend fun setupLink(access: TenantAccess, userId: Uuid): SetupLink = db.query {
        val member = find(access.tenant, userId)
        val account = checkNotNull(account(userId))
        requireNotPlatformAdmin(access, account)
        requireOnlyThisTenant(access, account)
        if (member.username == null) invalid("Dieses Konto meldet sich nicht mit Passwort an.")
        if (account.isDisabled) conflict("Das Konto ist deaktiviert.")
        issueSetupLink(userId, if (member.setupPending) NEW_ACCOUNT_LINK_VALIDITY else RESET_LINK_VALIDITY)
    }

    private data class Account(
        val displayName: String,
        val username: String?,
        val isPlatformAdmin: Boolean,
        val isDisabled: Boolean,
        val tenants: List<Uuid>
    )

    private fun account(userId: Uuid): Account? = Users.leftJoin(PasswordLogins)
        .select(Users.displayName, PasswordLogins.username, Users.isPlatformAdmin, Users.disabledAt)
        .where { Users.id eq userId }
        .singleOrNull()
        ?.let { row ->
            Account(
                displayName = row[Users.displayName],
                username = row.getOrNull(PasswordLogins.username),
                isPlatformAdmin = row[Users.isPlatformAdmin],
                isDisabled = row[Users.disabledAt] != null,
                tenants = TenantMembers.select(TenantMembers.tenantId)
                    .where { TenantMembers.userId eq userId }
                    .map { it[TenantMembers.tenantId].value }
            )
        }

    /** Platform admins are never affected by what tenant admins do. */
    private fun requireNotPlatformAdmin(access: TenantAccess, account: Account) {
        if (account.isPlatformAdmin && !access.user.isPlatformAdmin) {
            forbidden("Plattform-Admins können nur von Plattform-Admins geändert werden.")
        }
    }

    private fun requireOnlyThisTenant(access: TenantAccess, account: Account) {
        if (account.tenants.any { it != access.tenant.value } && !access.user.isPlatformAdmin) {
            forbidden("Das Konto gehört auch zu einem anderen Stamm. Nur Plattform-Admins können es ändern.")
        }
    }

    private fun String?.changes(current: String?) = this != null && this != current

    private fun find(tenant: TenantId, userId: Uuid): Member =
        findWhere { (TenantMembers.tenantId eq tenant.value) and (TenantMembers.userId eq userId) }.singleOrNull()
            ?: notFound("Mitglied", userId)

    private fun findWhere(predicate: () -> Op<Boolean>): List<Member> = TenantMembers
        .innerJoin(Users)
        .leftJoin(PasswordLogins)
        .select(TenantMembers.columns + Users.columns + PasswordLogins.columns)
        .where(predicate)
        .orderBy(Users.displayName)
        .map { row ->
            val username = row.getOrNull(PasswordLogins.username)
            Member(
                userId = row[Users.id].value,
                displayName = row[Users.displayName],
                username = username,
                role = row[TenantMembers.role],
                isDisabled = row[Users.disabledAt] != null,
                setupPending = username != null && row.getOrNull(PasswordLogins.passwordHash) == null,
                // The only login method so far.
                loginMethod = LoginMethod.PASSWORD
            )
        }
}
