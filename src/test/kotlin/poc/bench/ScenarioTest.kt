package poc.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScenarioTest {
    @Test
    fun `HOT_SINGLE has one item`() {
        assertEquals(1, Scenario.HOT_SINGLE.items().size)
    }

    @Test
    fun `LAST_UNITS has small stock`() {
        assertEquals(10, Scenario.LAST_UNITS.items().first().totalStock)
    }

    @Test
    fun `MIXED spreads across many items`() {
        assertEquals(1000, Scenario.MIXED.items().size)
    }
}
