package api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Request and response bodies for tenants, accounts and sessions. Same conventions as in Models.kt. Tokens are always
// sent in bodies, never in paths, so they don't end up in access logs.

/** Role of a member in a tenant. Each role includes the permissions of the previous one. */
@Serializable
@SerialName("TenantRole")
enum class TenantRole { VIEWER, EDITOR, ADMIN }

/** How a user logs in. Only password logins exist so far; external providers come later. */
@Serializable
@SerialName("LoginMethod")
enum class LoginMethod { PASSWORD }

// ========= Sessions =========

/** The username is case-insensitive; surrounding whitespace is ignored. */
@Serializable
data class Credentials(val username: String, val password: String) {
    init {
        requireMaxLength(username, "Der Benutzername", 100)
        requireMaxLength(password, "Das Passwort", MAX_PASSWORD_LENGTH)
    }
}

/** The token is sent as `Authorization: Bearer <token>`. Sessions also end after 30 days without use. */
@Serializable
data class SessionToken(val token: String, val expiresAt: Instant)

@Serializable
data class CurrentUser(
    val id: Uuid,
    val displayName: String,
    /** Null for users who log in via an external provider. */
    val username: String?,
    val isPlatformAdmin: Boolean,
    /** The tenants the user has access to, by name. Platform admins get all of them as ADMIN. */
    val tenants: List<UserTenant>
)

@Serializable
data class UserTenant(
    val id: Uuid,
    val name: String,
    val slug: String,
    val role: TenantRole,
    /** Whether the user is a member, as opposed to access as platform admin. */
    val isMember: Boolean
)

@Serializable
data class NewProfile(val displayName: String) {
    init {
        requireDisplayName(displayName)
    }
}

/** Ends all other sessions of the user. */
@Serializable
data class PasswordChange(val currentPassword: String, val newPassword: String) {
    init {
        requireMaxLength(currentPassword, "Das aktuelle Passwort", MAX_PASSWORD_LENGTH)
        requireNewPassword(newPassword)
    }
}

// ========= Setup links =========

/** A single-use link to set a password, for new accounts and resets. The frontend builds the link from the token. */
@Serializable
data class SetupLink(val token: String, val expiresAt: Instant)

@Serializable
data class SetupToken(val token: String)

/** Shown on the setup page, so the user knows which account they set up. */
@Serializable
data class SetupInfo(val displayName: String, val username: String)

/** Sets the password, ends all other sessions of the user and logs in. */
@Serializable
data class PasswordSetup(val token: String, val password: String) {
    init {
        requireNewPassword(password)
    }
}

// ========= Tenants (platform admins) =========

@Serializable
data class Tenant(
    val id: Uuid,
    val name: String,
    val slug: String,
    /** Set for deleted tenants, which can be restored. */
    val deletedAt: Instant?
)

/** [slug] is the tenant's part of frontend URLs; changing it breaks existing links. */
@Serializable
data class NewTenant(val name: String, val slug: String) {
    init {
        requireText(name, "Der Name", maxLength = 100)
        require(slug.matches(SLUG)) {
            "Das Kürzel darf nur aus Kleinbuchstaben, Ziffern und einzelnen Bindestrichen dazwischen bestehen."
        }
        requireMaxLength(slug, "Das Kürzel", 50)
        require(slug !in RESERVED_SLUGS) { "Das Kürzel $slug ist für eine Seite reserviert." }
    }

    companion object {
        private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

        /** Top-level paths of the frontend, which can't be tenants (the frontend checks its routes against this). */
        val RESERVED_SLUGS = setOf(
            "login", "logout", "setup", "account", "admin", "api",
            // Former top-level pages, redirected into a tenant.
            "materials", "kits", "loans", "locations", "overview",
        )
    }
}

// ========= Users (platform admins) =========

@Serializable
data class User(
    val id: Uuid,
    val displayName: String,
    /** Null for users who log in via an external provider. */
    val username: String?,
    val loginMethod: LoginMethod,
    val isPlatformAdmin: Boolean,
    val isDisabled: Boolean,
    /** The user hasn't set their password yet. */
    val setupPending: Boolean,
    val memberships: List<Membership>
)

@Serializable
data class Membership(val tenantId: Uuid, val role: TenantRole)

/**
 * The last enabled platform admin can't lose the flag or be disabled. Disabling ends the user's sessions. [username]
 * is only changed if given.
 */
@Serializable
data class UserUpdate(
    val displayName: String,
    val username: String? = null,
    val isPlatformAdmin: Boolean,
    val isDisabled: Boolean
) {
    init {
        requireDisplayName(displayName)
        username?.let(::requireUsername)
    }
}

// ========= Members of a tenant =========

@Serializable
data class Member(
    val userId: Uuid,
    val displayName: String,
    /** Null for users who log in via an external provider. */
    val username: String?,
    val role: TenantRole,
    /** Disabled users can't log in; only platform admins can disable and enable them. */
    val isDisabled: Boolean,
    /** The user hasn't set their password yet. */
    val setupPending: Boolean,
    val loginMethod: LoginMethod
)

/** Creates a new account with a password login, which the user sets up with the returned link. */
@Serializable
data class NewMember(val displayName: String, val username: String, val role: TenantRole) {
    init {
        requireDisplayName(displayName)
        requireUsername(username)
    }
}

@Serializable
data class CreatedMember(val member: Member, val setupLink: SetupLink)

/**
 * Changes a member's role, and optionally their account. Name and username can only be changed by tenant admins for
 * accounts that belong to their tenant only. Platform admins can also add users of other tenants this way.
 */
@Serializable
data class MemberUpdate(val role: TenantRole, val displayName: String? = null, val username: String? = null) {
    init {
        displayName?.let(::requireDisplayName)
        username?.let(::requireUsername)
    }
}

// ========= Validation helpers =========

/** Longer passwords aren't hashed at all, so they can't be used to keep the server busy. */
private const val MAX_PASSWORD_LENGTH = 200

private val USERNAME = Regex("^[a-z0-9._-]{3,100}$")

private fun requireUsername(value: String) = require(value.matches(USERNAME)) {
    "Der Benutzername muss 3 bis 100 Zeichen lang sein und darf nur Kleinbuchstaben, Ziffern, Punkt, Bindestrich und " +
        "Unterstrich enthalten."
}

internal fun requireDisplayName(value: String) = requireText(value, "Der Name", maxLength = 255)

private fun requireNewPassword(value: String) {
    require(value.length >= 12) { "Das Passwort muss mindestens 12 Zeichen lang sein." }
    requireMaxLength(value, "Das Passwort", MAX_PASSWORD_LENGTH)
}
