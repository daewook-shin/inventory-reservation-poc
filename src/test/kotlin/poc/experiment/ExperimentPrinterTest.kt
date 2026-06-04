package poc.experiment

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExperimentPrinterTest {
    @Test
    fun `E1 table shows isolation rows and conflict columns`() {
        val rr = DeadlockStats().apply { deadlocks.set(3); lockWaitTimeouts.set(5); successes.set(120) }
        val rc = DeadlockStats().apply { successes.set(500) }
        val md = ExperimentPrinter.e1Markdown(rrStats = rr, rcStats = rc)
        assertTrue(md.contains("REPEATABLE_READ"))
        assertTrue(md.contains("READ_COMMITTED"))
        assertTrue(md.contains("deadlocks"))
    }

    @Test
    fun `E2 table shows oversell and pool cap`() {
        val report = E2Report(
            total = 5000, successes = 5000, oversell = 0, conserved = true,
            maxPoolObserved = 1000,
            finalSnapshot = LedgerSnapshot(5000, 5000, 0, 0),
        )
        val md = ExperimentPrinter.e2Markdown(report)
        assertTrue(md.contains("oversell"))
        assertTrue(md.contains("maxPool"))
    }
}
