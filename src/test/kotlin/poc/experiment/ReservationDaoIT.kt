package poc.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager

@SpringBootTest
@ActiveProfiles("test")
class ReservationDaoIT @Autowired constructor(
    val dao: ReservationDao,
    val tm: PlatformTransactionManager,
) {
    private val rc get() = Tx.template(tm, Tx.READ_COMMITTED)

    @Test
    fun `seed materializes capped pool and reserve then claim moves to sold`() {
        dao.seed(itemId = 500, locationId = 1, ledgerTotal = 3000, poolCap = 1000)
        assertEquals(1000, dao.poolSize(500, 1))

        val rid = dao.reserve(rc, 500, 1, 1)!!
        var snap = dao.ledgerSnapshot(500, 1)
        assertEquals(1, snap.reserved); assertEquals(999, snap.pool); assertEquals(0, snap.sold)

        dao.claim(rc, rid)
        snap = dao.ledgerSnapshot(500, 1)
        assertEquals(0, snap.reserved); assertEquals(999, snap.pool); assertEquals(1, snap.sold)
    }

    @Test
    fun `reserve returns null when pool empty`() {
        dao.seed(itemId = 501, locationId = 1, ledgerTotal = 2, poolCap = 1000)
        assertEquals(2, dao.poolSize(501, 1))
        dao.reserve(rc, 501, 1, 1)
        dao.reserve(rc, 501, 1, 1)
        assertEquals(null, dao.reserve(rc, 501, 1, 1))
    }
}
