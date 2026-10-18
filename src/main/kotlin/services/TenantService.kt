package services

import api.TenantRole
import auth.TenantAccess
import auth.UserPrincipal
import database.*
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

class TenantService(private val db: Db) {

    /**
     * The access of [user] to the tenant [id]: their role as member, or ADMIN for platform admins. Null if the tenant
     * doesn't exist, is deleted, or the user has no access.
     */
    suspend fun access(user: UserPrincipal, id: Uuid): TenantAccess? = db.query {
        val row = Tenants.join(TenantMembers, JoinType.LEFT, Tenants.id, TenantMembers.tenantId) {
            TenantMembers.userId eq user.userId
        }
            .select(TenantMembers.role)
            .where { (Tenants.id eq id) and Tenants.deletedAt.isNull() }
            .singleOrNull() ?: return@query null
        val role = if (user.isPlatformAdmin) TenantRole.ADMIN else row.getOrNull(TenantMembers.role)
        role?.let { TenantAccess(TenantId(id), it, user) }
    }
}
