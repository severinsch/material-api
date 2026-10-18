package api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request and response bodies for tenants, accounts and sessions. Same conventions as in Models.kt.

/** Role of a member in a tenant. Each role includes the permissions of the previous one. */
@Serializable
@SerialName("TenantRole")
enum class TenantRole { VIEWER, EDITOR, ADMIN }

/** How a user logs in. Only password logins exist so far; external providers come later. */
@Serializable
@SerialName("LoginMethod")
enum class LoginMethod { PASSWORD }
