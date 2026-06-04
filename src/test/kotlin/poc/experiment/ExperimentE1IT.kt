package poc.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class ExperimentE1IT @Autowired constructor(
    val runner: ExperimentRunner,
) {
    @Test
    fun `E1 under READ COMMITTED completes with no lock conflicts`() {
        val stats = runner.runE1(isolation = Tx.READ_COMMITTED, clients = 50, rounds = 10)
        assertEquals(0, stats.deadlocks.get(), "RC should not deadlock")
        assertEquals(0, stats.lockWaitTimeouts.get(), "RC should not time out on locks")
        assertTrue(stats.successes.get() > 0, "expected some successful reservations")
    }
}
