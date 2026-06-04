package poc.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager

@SpringBootTest
@ActiveProfiles("test")
class ReplenishmentJobIT @Autowired constructor(
    val dao: ReservationDao,
    val job: ReplenishmentJob,
    val tm: PlatformTransactionManager,
) {
    private val rc get() = Tx.template(tm, Tx.READ_COMMITTED)

    @Test
    fun `replenish refills drained pool up to cap without exceeding it`() {
        dao.seed(itemId = 600, locationId = 1, ledgerTotal = 3000, poolCap = 1000)
        repeat(400) {
            val rid = dao.reserve(rc, 600, 1, 1)!!
            dao.claim(rc, rid)
        }
        assertEquals(600, dao.poolSize(600, 1))
        val inserted = job.replenishOnce(rc, 600, 1, poolCap = 1000)
        assertEquals(400, inserted)
        assertEquals(1000, dao.poolSize(600, 1))
    }

    @Test
    fun `replenish inserts nothing when pool already at cap`() {
        dao.seed(itemId = 601, locationId = 1, ledgerTotal = 3000, poolCap = 1000)
        assertEquals(0, job.replenishOnce(rc, 601, 1, poolCap = 1000))
        assertEquals(1000, dao.poolSize(601, 1))
    }

    @Test
    fun `replenish is bounded by unmaterialized remainder`() {
        dao.seed(itemId = 602, locationId = 1, ledgerTotal = 1200, poolCap = 1000)
        repeat(300) { val rid = dao.reserve(rc, 602, 1, 1)!!; dao.claim(rc, rid) }
        assertEquals(700, dao.poolSize(602, 1))
        assertEquals(200, job.replenishOnce(rc, 602, 1, poolCap = 1000))
        assertEquals(900, dao.poolSize(602, 1))
    }
}
