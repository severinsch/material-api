package routes

import api.Credentials
import api.NewProfile
import api.PasswordChange
import api.PasswordSetup
import api.SetupToken
import configure.SESSION
import configure.user
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.AuthService

fun Route.authRoutes(authService: AuthService) {
    route("/auth") {
        /**
         * Log in with username and password.
         *
         * Response: 401 application/json [api.ErrorResponse] Wrong username or password.
         * Response: 429 application/json [api.ErrorResponse] Too many failed attempts, see the Retry-After header.
         * Response: 503 application/json [api.ErrorResponse] Too many logins at the same time.
         */
        post("/login") {
            val credentials = call.receive<Credentials>()
            call.respond(authService.login(credentials, call.clientIp))
        }

        /**
         * Look up the account of a setup link, for the setup page.
         *
         * Response: 404 application/json [api.ErrorResponse] The link is invalid or expired.
         */
        post("/setup/inspect") {
            call.respond(authService.inspectSetup(call.receive<SetupToken>()))
        }

        /**
         * Set the password with a setup link, which is used up. Ends all sessions of the user and logs in.
         *
         * Response: 404 application/json [api.ErrorResponse] The link is invalid or expired.
         * Response: 503 application/json [api.ErrorResponse] Too many logins at the same time.
         */
        post("/setup") {
            call.respond(authService.completeSetup(call.receive<PasswordSetup>()))
        }

        authenticate(SESSION) {
            /**
             * End the current session.
             */
            post("/logout") {
                authService.logout(call.user)
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Get the current user and the tenants they have access to.
             */
            get("/me") {
                call.respond(authService.me(call.user))
            }

            /**
             * Change the own display name.
             */
            put("/me") {
                call.respond(authService.updateProfile(call.user, call.receive<NewProfile>()))
            }

            /**
             * Change the own password. Ends all other sessions of the user.
             *
             * Response: 400 application/json [api.ErrorResponse] The current password is wrong, or the new one too short.
             * Response: 429 application/json [api.ErrorResponse] Too many failed attempts, see the Retry-After header.
             * Response: 503 application/json [api.ErrorResponse] Too many logins at the same time.
             */
            put("/me/password") {
                authService.changePassword(call.user, call.receive<PasswordChange>(), call.clientIp)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/**
 * The client's IP address for throttling. The API is only reachable from the frontend, which forwards the address in
 * `X-Forwarded-For`.
 */
private val RoutingCall.clientIp: String
    get() = request.headers[HttpHeaders.XForwardedFor]?.substringBefore(',')?.trim()?.ifEmpty { null }
        ?: request.origin.remoteAddress
