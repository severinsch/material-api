package auth

import services.TooManyRequestsException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Slows down password guessing. Kept in memory, which is enough for a single instance.
 *
 * - Per username and client IP: after [FREE_FAILURES] failed attempts, every further attempt has to wait, starting
 *   at [FIRST_DELAY] and doubling with each failure up to [MAX_DELAY]. A success resets it, and so does a day without
 *   failures. Throttling per username alone would let anyone keep a known username such as `admin` locked out.
 * - Per client IP: at most [MAX_FAILURES_PER_IP] failures per [IP_WINDOW], across all usernames.
 */
class LoginThrottle(private val clock: Clock = Clock.System) {
    private data class UsernameFailures(val count: Int, val last: Instant)
    private data class IpFailures(val count: Int, val since: Instant)

    private val byUsername = ConcurrentHashMap<Pair<String, String>, UsernameFailures>()
    private val byIp = ConcurrentHashMap<String, IpFailures>()

    /** Throws [TooManyRequestsException] if [username] can't be tried from [ip] yet. */
    fun check(username: String, ip: String) {
        val now = clock.now()
        val wait = listOfNotNull(
            byUsername[username to ip]?.let { it.last + delayAfter(it.count) - now },
            byIp[ip]?.takeIf { it.count >= MAX_FAILURES_PER_IP }?.let { it.since + IP_WINDOW - now },
        ).maxOrNull()
        if (wait != null && wait.isPositive()) {
            throw TooManyRequestsException("Zu viele fehlgeschlagene Anmeldungen. Versuch es ${inGerman(wait)} noch einmal.", wait)
        }
    }

    fun failed(username: String, ip: String) {
        val now = clock.now()
        byUsername.merge(username to ip, UsernameFailures(1, now)) { old, new ->
            if (old.last + FORGET_AFTER <= now) new else UsernameFailures(old.count + 1, now)
        }
        byIp.merge(ip, IpFailures(1, now)) { old, new ->
            if (old.since + IP_WINDOW <= now) new else old.copy(count = old.count + 1)
        }
    }

    fun succeeded(username: String, ip: String) {
        byUsername.remove(username to ip)
    }

    /** Forgets failures that no longer count, so the maps don't grow. */
    fun cleanUp() {
        val now = clock.now()
        byUsername.values.removeIf { it.last + FORGET_AFTER <= now }
        byIp.values.removeIf { it.since + IP_WINDOW <= now }
    }

    private fun delayAfter(failures: Int): Duration =
        if (failures < FREE_FAILURES) Duration.ZERO
        else minOf(MAX_DELAY, FIRST_DELAY * (1 shl minOf(failures - FREE_FAILURES, 20)))

    private fun inGerman(wait: Duration): String {
        val seconds = wait.inWholeSeconds + 1
        val minutes = (seconds + 59) / 60
        return when {
            seconds == 1L -> "in einer Sekunde"
            seconds < 60 -> "in $seconds Sekunden"
            minutes == 1L -> "in einer Minute"
            else -> "in $minutes Minuten"
        }
    }

    companion object {
        const val FREE_FAILURES = 5
        val FIRST_DELAY = 1.seconds
        val MAX_DELAY = 15.minutes
        val FORGET_AFTER = 24.hours
        const val MAX_FAILURES_PER_IP = 50
        val IP_WINDOW = 1.hours
    }
}
