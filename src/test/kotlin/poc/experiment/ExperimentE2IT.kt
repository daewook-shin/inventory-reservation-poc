package poc.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class ExperimentE2IT @Autowired constructor(
    val runner: ExperimentRunner,
) {
    @Test
    fun `E2 bounded pool never oversells and pool stays within cap`() {
        val report = runner.runE2(clients = 100, rounds = 20, ledgerTotal = 5000, poolCap = 1000)
        assertEquals(0, report.oversell)
        assertTrue(report.maxPoolObserved <= 1000, "pool exceeded cap: ${report.maxPoolObserved}")
        assertTrue(report.conserved, "conservation violated")
        assertTrue(report.successes <= 5000, "claimed more than total: ${report.successes}")
    }
}
