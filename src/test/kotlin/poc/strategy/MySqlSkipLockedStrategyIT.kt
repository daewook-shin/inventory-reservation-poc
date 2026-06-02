package poc.strategy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import poc.bench.CorrectnessOracle
import poc.domain.ItemSpec
import poc.domain.ReservationOutcome

@SpringBootTest
class MySqlSkipLockedStrategyIT @Autowired constructor(
    val strategy: MySqlSkipLockedStrategy,
) {
    @Test
    fun `reserve then claim moves unit from available to sold`() {
        strategy.reset(listOf(ItemSpec(itemId = 100, locationId = 1, totalStock = 5)))
        val r = strategy.reserve(100, 1, 1)
        assertEquals(ReservationOutcome.SUCCESS, r.outcome)
        var snap = strategy.snapshot(100, 1)
        assertEquals(1, snap.reserved); assertEquals(4, snap.available); assertEquals(0, snap.sold)
        strategy.claim(r.reservationId!!)
        snap = strategy.snapshot(100, 1)
        assertEquals(0, snap.reserved); assertEquals(4, snap.available); assertEquals(1, snap.sold)
    }

    @Test
    fun `concurrent reserve on last units never oversells`() = runBlocking {
        strategy.reset(listOf(ItemSpec(itemId = 101, locationId = 1, totalStock = 10)))
        val jobs = (1..200).map {
            async(Dispatchers.IO) {
                val r = strategy.reserve(101, 1, 1)
                if (r.outcome == ReservationOutcome.SUCCESS) strategy.claim(r.reservationId!!)
                r.outcome == ReservationOutcome.SUCCESS
            }
        }
        val successes = jobs.awaitAll().count { it }
        assertEquals(10, successes) // 정확히 재고만큼만 성공
        val report = CorrectnessOracle.check(strategy.snapshot(101, 1), total = 10)
        assertEquals(0, report.oversell)
    }
}
