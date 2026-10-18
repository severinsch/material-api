package services

import api.SetupLink
import database.Db
import database.PasswordLogins
import database.Users
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/** Keeps the platform administration reachable without editing the database. Runs on startup. */
class BootstrapService(private val db: Db) {

    /**
     * With [createAdmin], creates the platform admin [ADMIN] without password if there is no platform admin at all.
     * Returns the setup links to log, by username: one for [ADMIN] as long as its password isn't set (renewed on every
     * start), and one for [recoveryUsername], a platform admin who forgot their password.
     */
    suspend fun run(createAdmin: Boolean, recoveryUsername: String?): Map<String, SetupLink> = db.query {
        if (createAdmin && Users.select(Users.id).where { Users.isPlatformAdmin eq true }.empty()) {
            check(PasswordLogins.select(PasswordLogins.userId).where { PasswordLogins.username eq ADMIN }.empty()) {
                "There is no platform admin, but the username $ADMIN is taken. Rename that user, or make an existing " +
                    "user platform admin in the database (users.is_platform_admin)."
            }
            val id = Users.insertAndGetId {
                it[displayName] = "Administrator"
                it[isPlatformAdmin] = true
            }
            PasswordLogins.insert {
                it[userId] = id
                it[username] = ADMIN
            }
        }
        buildMap {
            if (createAdmin) {
                userId(ADMIN, pendingSetup = true)?.let { put(ADMIN, issueSetupLink(it, NEW_ACCOUNT_LINK_VALIDITY)) }
            }
            recoveryUsername?.let { username ->
                val id = checkNotNull(userId(username)) {
                    "ADMIN_SETUP_LINK_FOR: $username isn't an enabled platform admin with a password login."
                }
                put(username, issueSetupLink(id, RESET_LINK_VALIDITY))
            }
        }
    }

    /** The enabled platform admin with this username, optionally only while their setup is pending. */
    private fun userId(username: String, pendingSetup: Boolean = false): Uuid? = PasswordLogins.innerJoin(Users)
        .select(Users.id)
        .where {
            (PasswordLogins.username eq username) and (Users.isPlatformAdmin eq true) and Users.disabledAt.isNull() and
                (if (pendingSetup) PasswordLogins.passwordHash.isNull() else Op.TRUE)
        }
        .singleOrNull()?.get(Users.id)?.value

    companion object {
        const val ADMIN = "admin"
    }
}
