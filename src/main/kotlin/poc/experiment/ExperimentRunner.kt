package poc.experiment

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import poc.domain.SHOP_ID

data class E2Report(
    val total: Long,
    val successes: Long,
    val oversell: Long,
    val conserved: Boolean,
    val maxPoolObserved: Long,
    val finalSnapshot: LedgerSnapshot,
)

private const val ITEM = 9000L
private const val LOC = 1L
private const val DEMO_ITEM = 9100L

@Component
class ExperimentRunner(
    private val tm: PlatformTransactionManager,
    private val dao: ReservationDao,
    private val replenishment: ReplenishmentJob,
    private val jdbc: JdbcTemplate,
) {
    /** DataAccessException을 InnoDB 에러코드로 분류해 stats에 누적. */
    fun classify(e: Throwable, stats: DeadlockStats) {
        val sql = (e as? DataAccessException)?.mostSpecificCause as? SQLException
        when (sql?.errorCode) {
            1213 -> stats.deadlocks.incrementAndGet()
            1205 -> stats.lockWaitTimeouts.incrementAndGet()
            else -> stats.otherErrors.incrementAndGet()
        }
    }

    /**
     * E2: total > poolCap 으로 세팅하고 reserve→claim 부하 + replenishment 동시 구동.
     * 풀 상한 준수와 oversell 0 을 검증할 수 있는 리포트를 반환.
     */
    fun runE2(clients: Int, rounds: Int, ledgerTotal: Int, poolCap: Int): E2Report = runBlocking {
        dao.seed(ITEM, LOC, ledgerTotal, poolCap)
        val rc = Tx.template(tm, Tx.READ_COMMITTED)
        var maxPool = dao.poolSize(ITEM, LOC)

        val replenisher = launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    replenishment.replenishOnce(rc, ITEM, LOC, poolCap)
                    val p = dao.poolSize(ITEM, LOC)
                    synchronized(this@ExperimentRunner) { if (p > maxPool) maxPool = p }
                } catch (e: Exception) { /* E2는 RC라 충돌 거의 없음; 무시하고 계속 */ }
                delay(5)
            }
        }

        repeat(rounds) {
            (0 until clients).map {
                async(Dispatchers.IO) {
                    try {
                        val rid = dao.reserve(rc, ITEM, LOC, 1)
                        if (rid != null) dao.claim(rc, rid)
                    } catch (_: Exception) { /* RC 경로 충돌은 E2 관심사 아님 */ }
                }
            }.awaitAll()
            val p = dao.poolSize(ITEM, LOC)
            synchronized(this@ExperimentRunner) { if (p > maxPool) maxPool = p }
        }
        replenisher.cancelAndJoin()

        val snap = dao.ledgerSnapshot(ITEM, LOC)
        E2Report(
            total = snap.total,
            successes = snap.sold,
            oversell = snap.oversell,
            conserved = snap.conserved,
            maxPoolObserved = maxPool,
            finalSnapshot = snap,
        )
    }

    /**
     * E1: total > poolCap 으로 replenishment INSERT가 계속 일어나게 하고,
     * 주어진 격리수준으로 reserve(SKIP LOCKED SELECT + DELETE + INSERT)를 동시 구동.
     * REPEATABLE READ 에서는 SKIP LOCKED select의 supremum gap lock이 replenishment INSERT와
     * 충돌해 데드락/타임아웃이 발생할 수 있다. READ COMMITTED 는 gap lock이 없어 깨끗하다.
     */
    fun runE1(isolation: Int, clients: Int, rounds: Int): DeadlockStats = runBlocking {
        val poolCap = 1000
        dao.seed(ITEM, LOC, ledgerTotal = 3000, poolCap = poolCap)
        val stats = DeadlockStats()
        val reserveTx = Tx.template(tm, isolation)
        val replenishTx = Tx.template(tm, Tx.READ_COMMITTED) // 보충은 RC 고정; reserve 격리수준이 변수

        val replenisher = launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    replenishment.replenishOnce(replenishTx, ITEM, LOC, poolCap)
                } catch (e: Exception) {
                    classify(e, stats) // 보충 INSERT가 gap lock에 막혀 실패한 것도 충돌로 집계
                }
                delay(2)
            }
        }

        repeat(rounds) {
            (0 until clients).map {
                async(Dispatchers.IO) {
                    try {
                        val rid = dao.reserve(reserveTx, ITEM, LOC, 1)
                        if (rid != null) {
                            stats.successes.incrementAndGet()
                            dao.claim(reserveTx, rid) // 풀을 계속 비워 replenishment가 INSERT를 반복하게 함
                        } else {
                            stats.soldOut.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        classify(e, stats)
                    }
                }
            }.awaitAll()
        }
        replenisher.cancelAndJoin()
        stats
    }

    /**
     * gap-lock 데드락 최소 재현. 두 트랜잭션이 같은 범위에 gap lock을 잡은 뒤 동시에 INSERT.
     * REPEATABLE READ: gap lock 보유 → insert-intention 순환 대기 → 데드락(1213).
     * READ COMMITTED: gap lock 없음 → 양쪽 INSERT 성공.
     */
    fun runGapLockDemo(isolation: Int, rounds: Int = 20): DeadlockStats {
        val stats = DeadlockStats()
        val tx = Tx.template(tm, isolation)
        repeat(rounds) {
            seedDemoRows()
            val barrier = CyclicBarrier(2)
            val threads = (0..1).map { k ->
                thread {
                    try {
                        tx.executeWithoutResult {
                            jdbc.queryForList(
                                "SELECT id FROM reservation_units WHERE shop_id=? AND item_id=? AND location_id=? AND id > 0 FOR UPDATE",
                                Long::class.java, SHOP_ID, DEMO_ITEM, LOC,
                            )
                            try { barrier.await(10, TimeUnit.SECONDS) } catch (_: Exception) { /* 한쪽이 먼저 실패해도 INSERT 진행 */ }
                            jdbc.update(
                                "INSERT INTO reservation_units(shop_id,item_id,location_id,id) VALUES (?,?,?,?)",
                                SHOP_ID, DEMO_ITEM, LOC, 1000L + k,
                            )
                        }
                        stats.successes.incrementAndGet()
                    } catch (e: Throwable) {
                        classify(e, stats)
                    }
                }
            }
            threads.forEach { it.join() }
        }
        return stats
    }

    private fun seedDemoRows() {
        // 빈 테이블로 세팅: gap lock만 잡히고 next-key lock이 없어야 두 SELECT가 동시에 진행된다.
        jdbc.update("DELETE FROM reservation_units WHERE shop_id=? AND item_id=? AND location_id=?", SHOP_ID, DEMO_ITEM, LOC)
    }
}
