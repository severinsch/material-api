package services

import api.NewTenant
import api.Tenant
import api.TenantRole
import auth.TenantAccess
import auth.UserPrincipal
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Tenants: access for the tenant routes, and their administration by platform admins. */
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

    /** All tenants including deleted ones, by name. */
    suspend fun getAll(): List<Tenant> = db.query {
        Tenants.selectAll().orderBy(Tenants.name).map(::toTenant)
    }

    suspend fun create(tenant: NewTenant): Tenant = db.query {
        val id = Tenants.insertAndGetId { it.fill(tenant) }
        find(id.value)
    }

    /** Also for deleted tenants. Changing the slug breaks existing links. */
    suspend fun update(id: Uuid, tenant: NewTenant): Tenant = db.query {
        if (Tenants.update({ Tenants.id eq id }) { it.fill(tenant) } == 0) notFound("Stamm", id)
        find(id)
    }

    /** Soft delete: the tenant and its data become inaccessible, but can be restored. */
    suspend fun delete(id: Uuid) = db.query {
        val deleted = Tenants.update({ (Tenants.id eq id) and Tenants.deletedAt.isNull() }) {
            it[deletedAt] = Clock.System.now()
        }
        if (deleted == 0) notFound("Stamm", id)
    }

    /** Fails with 409 if another tenant took the name or slug in the meantime. */
    suspend fun restore(id: Uuid): Tenant = db.query {
        if (Tenants.update({ Tenants.id eq id }) { it[deletedAt] = null } == 0) notFound("Stamm", id)
        find(id)
    }

    private fun find(id: Uuid): Tenant =
        Tenants.selectAll().where { Tenants.id eq id }.singleOrNull()?.let(::toTenant) ?: notFound("Stamm", id)

    private fun toTenant(row: ResultRow) = Tenant(
        id = row[Tenants.id].value,
        name = row[Tenants.name],
        slug = row[Tenants.slug],
        deletedAt = row[Tenants.deletedAt]
    )

    private fun UpdateBuilder<*>.fill(tenant: NewTenant) {
        this[Tenants.name] = tenant.name
        this[Tenants.slug] = tenant.slug
    }
}
