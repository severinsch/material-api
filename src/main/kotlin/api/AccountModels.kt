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
data class Credentials(val username: String, val password: String)

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

// ========= Validation helpers =========

internal fun requireDisplayName(value: String) = requireText(value, "Der Name", maxLength = 255)

private fun requireNewPassword(value: String) {
    require(value.length >= 12) { "Das Passwort muss mindestens 12 Zeichen lang sein." }
    requireMaxLength(value, "Das Passwort", 200)
}
