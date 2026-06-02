package poc.strategy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import poc.domain.ItemSpec
import poc.domain.ReservationOutcome

@SpringBootTest
class RedisLedgerSplitStrategyIT @Autowired constructor(
    val strategy: RedisLedgerSplitStrategy,
) {
    @Test
    fun `concurrent reserve on last units oversells (gap reproduced)`() = runBlocking {
        strategy.reset(listOf(ItemSpec(itemId = 300, locationId = 1, totalStock = 10)))
        val jobs = (1..200).map {
            async(Dispatchers.IO) {
                val r = strategy.reserve(300, 1, 1)
                if (r.outcome == ReservationOutcome.SUCCESS) strategy.claim(r.reservationId!!)
                r.outcome == ReservationOutcome.SUCCESS
            }
        }
        val successes = jobs.awaitAll().count { it }
        // atomicity gap: 재고 10인데 11건 이상 성공해야 결함이 재현된 것
        assertTrue(successes > 10, "expected oversell but got $successes successes")
    }
}
