import auth.Argon2Config
import auth.LoginThrottle
import auth.Passwords
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import services.BusyException
import services.TooManyRequestsException
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AuthUnitTest {

    @Test
    fun `passwords are hashed with salt and verified`() = runBlocking {
        val passwords = TestDatabase.passwords
        val hash = passwords.hash("correct horse")
        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=64,t=1,p=1\$"), hash)
        assertNotEquals(hash, passwords.hash("correct horse"))
        assertTrue(passwords.verify("correct horse", hash))
        assertFalse(passwords.verify("Correct horse", hash))
        assertFalse(passwords.verify("correct horse", null))
        // Hashes keep their parameters, so they still verify after the configuration changed.
        assertTrue(Passwords(Argon2Config(memoryKib = 128, iterations = 2)).verify("correct horse", hash))
    }

    @Test
    fun `at most two hashes run at the same time`() = runBlocking {
        val passwords = Passwords(Argon2Config(memoryKib = 64, iterations = 1))
        // All three start before the first can finish: hashing runs on another dispatcher, and finishing resumes here.
        val results = List(3) { async { runCatching { passwords.hash("correct horse") } } }.awaitAll()
        assertEquals(2, results.count { it.isSuccess })
        assertIs<BusyException>(results.last().exceptionOrNull())
        passwords.hash("correct horse")
        Unit
    }

    @Test
    fun `failed logins delay further attempts more and more`() {
        val clock = TestClock()
        val throttle = LoginThrottle(clock)
        repeat(LoginThrottle.FREE_FAILURES) {
            throttle.check("max", "ip")
            throttle.failed("max", "ip")
        }
        assertEquals(1.seconds, waitTime { throttle.check("max", "ip") })
        throttle.check("max", "other ip")
        throttle.check("moritz", "ip")

        clock.now += 1.seconds
        throttle.check("max", "ip")
        throttle.failed("max", "ip")
        assertEquals(2.seconds, waitTime { throttle.check("max", "ip") })

        repeat(20) { throttle.failed("max", "ip") }
        assertEquals(LoginThrottle.MAX_DELAY, waitTime { throttle.check("max", "ip") })

        throttle.succeeded("max", "ip")
        throttle.check("max", "ip")
    }

    @Test
    fun `failures are forgotten after a day`() {
        val clock = TestClock()
        val throttle = LoginThrottle(clock)
        repeat(10) { throttle.failed("max", "ip") }
        clock.now += 1.hours
        throttle.check("max", "ip")
        throttle.failed("max", "ip")
        assertTrue(waitTime { throttle.check("max", "ip") } > 15.seconds, "still counted after an hour")

        clock.now += LoginThrottle.FORGET_AFTER
        throttle.failed("max", "ip")
        throttle.check("max", "ip")
    }

    @Test
    fun `an IP can only fail a limited number of times`() {
        val clock = TestClock()
        val throttle = LoginThrottle(clock)
        repeat(LoginThrottle.MAX_FAILURES_PER_IP) { throttle.failed("user$it", "ip") }
        assertEquals(LoginThrottle.IP_WINDOW, waitTime { throttle.check("someone", "ip") })
        throttle.check("someone", "other ip")

        clock.now += 30.minutes
        throttle.cleanUp()
        assertEquals(30.minutes, waitTime { throttle.check("someone", "ip") })
        clock.now += 30.minutes
        throttle.check("someone", "ip")
    }

    private fun waitTime(block: () -> Unit): Duration = assertFailsWith<TooManyRequestsException> { block() }.retryAfter

    private class TestClock(var now: Instant = Clock.System.now()) : Clock {
        override fun now() = now
    }
}
