package poc.experiment

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
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

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

@Component
class ExperimentRunner(
    private val tm: PlatformTransactionManager,
    private val dao: ReservationDao,
    private val replenishment: ReplenishmentJob,
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
}
