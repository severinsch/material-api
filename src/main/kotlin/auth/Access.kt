package auth

import api.TenantRole
import database.TenantId
import kotlin.uuid.Uuid

/** The user of an authenticated call, resolved from the session token on every request. */
data class UserPrincipal(val userId: Uuid, val sessionId: Uuid, val displayName: String, val isPlatformAdmin: Boolean)

/**
 * [BORROW]: lend materials to oneself, and see and change one's own open loans. [EDIT] includes all loans, see
 * services.LoanService.
 */
enum class Permission { READ, REPORT_DAMAGE, BORROW, EDIT, VIEW_MEMBERS, MANAGE_MEMBERS }

/**
 * What each role may do; the only place that decides this. Login providers (later hitobito) only map their users to a
 * role.
 */
val TenantRole.permissions: Set<Permission>
    get() = when (this) {
        TenantRole.VIEWER -> setOf(Permission.READ, Permission.REPORT_DAMAGE, Permission.BORROW)
        TenantRole.EDITOR -> TenantRole.VIEWER.permissions + setOf(Permission.EDIT, Permission.VIEW_MEMBERS)
        TenantRole.ADMIN -> TenantRole.EDITOR.permissions + Permission.MANAGE_MEMBERS
    }

/** The access of a user to a tenant: their membership role, or [TenantRole.ADMIN] for platform admins. */
data class TenantAccess(val tenant: TenantId, val role: TenantRole, val user: UserPrincipal) {
    fun permits(permission: Permission) = permission in role.permissions
}
