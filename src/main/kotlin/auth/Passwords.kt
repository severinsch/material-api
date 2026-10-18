package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import services.BusyException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

/** Argon2id cost parameters. The defaults are OWASP's recommendation; tests use cheaper ones. */
data class Argon2Config(val memoryKib: Int = 19 * 1024, val iterations: Int = 2, val parallelism: Int = 1)

/**
 * Hashes and verifies passwords with Argon2id. Hashes are PHC strings (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`) that
 * carry their own parameters, so the parameters can be raised later without invalidating existing hashes.
 *
 * Every hash allocates [Argon2Config.memoryKib] of memory, so at most [MAX_CONCURRENT] run at a time; further calls
 * fail fast with [BusyException] instead of queuing.
 */
class Passwords(private val config: Argon2Config) {
    private val random = SecureRandom()
    private val permits = Semaphore(MAX_CONCURRENT)

    /** Verified instead of a missing hash, so unknown users take as long as known ones (from the first login on). */
    private val dummyHash = hashBlocking(Tokens.generate())

    suspend fun hash(password: String): String = limited { hashBlocking(password) }

    /** Whether [password] matches [hash]. A null [hash] (unknown user, no password set) never matches. */
    suspend fun verify(password: String, hash: String?): Boolean = limited {
        val matches = verifyBlocking(password, hash ?: dummyHash)
        matches && hash != null
    }

    private suspend fun <T> limited(block: () -> T): T {
        if (!permits.tryAcquire()) throw BusyException("Gerade melden sich zu viele an. Versuch es gleich noch einmal.")
        try {
            return withContext(Dispatchers.Default) { block() }
        } finally {
            permits.release()
        }
    }

    private fun hashBlocking(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, config, HASH_BYTES)
        val params = "m=${config.memoryKib},t=${config.iterations},p=${config.parallelism}"
        return "\$argon2id\$v=19\$$params\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    private fun verifyBlocking(password: String, phc: String): Boolean {
        val parts = phc.split('$')
        require(parts.size == 6 && parts[1] == "argon2id" && parts[2] == "v=19") { "Unsupported password hash" }
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val config = Argon2Config(params.getValue("m"), params.getValue("t"), params.getValue("p"))
        val expected = decoder.decode(parts[5])
        return MessageDigest.isEqual(derive(password, decoder.decode(parts[4]), config, expected.size), expected)
    }

    private fun derive(password: String, salt: ByteArray, config: Argon2Config, length: Int): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(config.memoryKib)
            .withIterations(config.iterations)
            .withParallelism(config.parallelism)
            .withSalt(salt)
            .build()
        return ByteArray(length).also {
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password.toByteArray(Charsets.UTF_8), it)
        }
    }

    companion object {
        /** Hashes running at the same time; each allocates its memory on the (small) heap. */
        const val MAX_CONCURRENT = 2
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32

        // PHC strings use standard base64 without padding.
        private val encoder = Base64.getEncoder().withoutPadding()
        private val decoder = Base64.getDecoder()
    }
}
