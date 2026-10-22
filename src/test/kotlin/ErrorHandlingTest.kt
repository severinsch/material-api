import configure.databaseError
import io.ktor.http.*
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Database errors that are mapped to responses without a constraint; constraint violations are tested via the API. */
class ErrorHandlingTest {

    @Test
    fun `transactions that clash with concurrent ones are conflicts`() {
        // As thrown by Exposed once its retries are used up: the driver's exception as cause.
        fun failed(sqlState: String) = SQLException("Transaction failed", null, SQLException("…", sqlState))

        val message = "Jemand anderes hat gleichzeitig dieselben Daten geändert. Versuche es noch einmal."
        assertEquals(HttpStatusCode.Conflict to message, databaseError(failed("40P01")))
        assertEquals(HttpStatusCode.Conflict to message, databaseError(failed("40001")))
        // E.g. a lost connection, which stays an internal error.
        assertNull(databaseError(failed("08006")))
    }
}
