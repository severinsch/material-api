package auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

/** Session and setup tokens: random values handed to the client once, of which only a hash is stored. */
object Tokens {
    private val random = SecureRandom()

    /** A new random token, 256 bits as base64url. */
    fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** What is stored and looked up instead of the token. Tokens are random, so a fast hash is enough. */
    fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
}
