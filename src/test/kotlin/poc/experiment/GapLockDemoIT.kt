package poc.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class GapLockDemoIT @Autowired constructor(
    val runner: ExperimentRunner,
) {
    @Test
    fun `REPEATABLE READ reproduces gap-lock deadlock, READ COMMITTED does not`() {
        val rr = runner.runGapLockDemo(Tx.REPEATABLE_READ, rounds = 20)
        val rc = runner.runGapLockDemo(Tx.READ_COMMITTED, rounds = 20)
        assertTrue(rr.deadlocks.get() > 0, "RR should reproduce deadlocks, got ${rr.deadlocks.get()}")
        assertEquals(0, rc.deadlocks.get(), "RC should not deadlock")
    }
}
