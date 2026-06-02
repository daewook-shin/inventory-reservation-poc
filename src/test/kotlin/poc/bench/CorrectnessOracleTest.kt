package poc.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import poc.domain.InventorySnapshot

class CorrectnessOracleTest {
    @Test
    fun `conserved snapshot has zero oversell`() {
        val report = CorrectnessOracle.check(InventorySnapshot(sold = 4, reserved = 2, available = 4), total = 10)
        assertTrue(report.conserved)
        assertEquals(0, report.oversell)
    }

    @Test
    fun `oversell detected when sold plus reserved exceeds total`() {
        val report = CorrectnessOracle.check(InventorySnapshot(sold = 8, reserved = 5, available = 0), total = 10)
        assertFalse(report.conserved)
        assertEquals(3, report.oversell)
    }
}
