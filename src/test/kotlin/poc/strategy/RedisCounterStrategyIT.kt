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
class RedisCounterStrategyIT @Autowired constructor(
    val strategy: RedisCounterStrategy,
) {
    @Test
    fun `concurrent reserve on last units never oversells`() = runBlocking {
        strategy.reset(listOf(ItemSpec(itemId = 200, locationId = 1, totalStock = 10)))
        val jobs = (1..200).map {
            async(Dispatchers.IO) {
                val r = strategy.reserve(200, 1, 1)
                if (r.outcome == ReservationOutcome.SUCCESS) strategy.claim(r.reservationId!!)
                r.outcome == ReservationOutcome.SUCCESS
            }
        }
        val successes = jobs.awaitAll().count { it }
        assertEquals(10, successes)
        val report = CorrectnessOracle.check(strategy.snapshot(200, 1), total = 10)
        assertEquals(0, report.oversell) // 순수 Redis 단일 시스템은 정합성 OK (의도된 결과)
    }
}
