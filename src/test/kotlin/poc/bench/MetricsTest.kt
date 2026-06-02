package poc.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MetricsTest {
    @Test
    fun `percentile picks nearest-rank value`() {
        val latencies = (1..100).map { it.toLong() } // 1..100 ns
        val m = LatencyStats.from(latencies)
        assertEquals(50L, m.p50)
        assertEquals(95L, m.p95)
        assertEquals(99L, m.p99)
    }

    @Test
    fun `merge sums counts and combines latencies`() {
        val a = ClientResult(success = 2, soldOut = 1, latenciesNs = mutableListOf(10, 20))
        val b = ClientResult(success = 3, soldOut = 0, latenciesNs = mutableListOf(30))
        val merged = listOf(a, b).merge()
        assertEquals(5, merged.success)
        assertEquals(1, merged.soldOut)
        assertEquals(3, merged.latenciesNs.size)
    }
}
