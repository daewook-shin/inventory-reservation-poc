# 재고 예약 PoC — Phase 2 구현 계획 (DB 병리 동작 재현)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** replenishment 잡을 도입해 REPEATABLE READ의 gap-lock 데드락(E1)과 bounded pool 정합성(E2)을 로컬에서 재현·측정한다.

**Architecture:** `poc.experiment` 패키지에 격리수준을 파라미터로 받는 `ReservationDao`, 원장 기준으로 풀을 보충하는 `ReplenishmentJob`, 동시 부하를 구동하고 락 충돌/정합성을 집계하는 `ExperimentRunner`를 둔다. Phase 1 전략/벤치 코드는 불변. 기존 `BenchmarkCommandLineRunner`에 `--experiment` 분기만 추가한다.

**Tech Stack:** Phase 1과 동일 (Kotlin, Spring Boot 3.4 JDBC, MySQL 8.0, Jedis 미사용, kotlinx-coroutines, JUnit5). Spring의 `DataAccessException`/`mostSpecificCause`로 InnoDB 에러코드(1213 deadlock, 1205 lock-wait timeout)를 분류한다.

> **공통 전제:** 통합 테스트·실행은 `docker compose up -d`로 MySQL(localhost:3306)이 떠 있어야 한다.
> **누적 정합성 모델:** ledger.total = 실제 총 재고, ledger.sold = claim 완료 수, reserved = COUNT(reserved_quantities), pool = COUNT(reservation_units). 아직 행으로 구현되지 않은 재고 unmaterialized = total − sold − reserved − pool. replenishment는 `min(poolCap − pool, unmaterialized)`만큼 새 unit 행을 INSERT한다. oversell = max(0, sold + reserved − total).

---

### Task 1: 인프라 락 타임아웃 단축 + ExperimentConfig + ReservationDao

**Files:**
- Modify: `docker-compose.yml` (mysql command에 짧은 lock-wait timeout 추가)
- Create: `src/main/kotlin/poc/experiment/ExperimentConfig.kt`
- Create: `src/main/kotlin/poc/experiment/ReservationDao.kt`
- Test: `src/test/kotlin/poc/experiment/ReservationDaoIT.kt`

> InnoDB 기본 `innodb_lock_wait_timeout`은 50초라, E1에서 충돌 시 최대 50초 멈춘다. 3초로 낮춰 타임아웃이 빨리 표면화되게 한다. (Phase 1은 저경합이라 영향 없음.)

- [ ] **Step 1: docker-compose.yml의 mysql command 수정**

기존:
```yaml
    command: ["--transaction-isolation=READ-COMMITTED", "--innodb-thread-concurrency=0"]
```
로 되어 있는 줄을 아래로 교체:
```yaml
    command: ["--transaction-isolation=READ-COMMITTED", "--innodb-thread-concurrency=0", "--innodb-lock-wait-timeout=3"]
```

- [ ] **Step 2: 인프라 재생성 (스키마 재적용)**

Run: `docker compose up -d --force-recreate mysql`
그리고 컨테이너가 healthy 될 때까지 대기 (`docker compose ps`로 확인; 폴링 루프 사용, foreground `sleep` 금지).
Expected: mysql healthy. (볼륨이 없어 DB가 초기화되며, 다음 앱/테스트 기동 시 Flyway가 스키마를 재적용한다.)

- [ ] **Step 3: ExperimentConfig.kt 작성**

```kotlin
package poc.experiment

import java.util.concurrent.atomic.AtomicLong
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** InnoDB 락 충돌 카운트. 여러 코루틴에서 안전하게 증가시킨다. */
class DeadlockStats {
    val deadlocks = AtomicLong()          // MySQL 1213
    val lockWaitTimeouts = AtomicLong()   // MySQL 1205
    val otherErrors = AtomicLong()
    val successes = AtomicLong()
    val soldOut = AtomicLong()
}

/** 원장 + 풀 상태 스냅샷. */
data class LedgerSnapshot(val total: Long, val sold: Long, val reserved: Long, val pool: Long) {
    val oversell: Long get() = (sold + reserved - total).coerceAtLeast(0)
    val conserved: Boolean get() = sold + reserved <= total
}

/** 주어진 격리수준의 TransactionTemplate을 만든다. */
object Tx {
    fun template(tm: PlatformTransactionManager, isolation: Int): TransactionTemplate =
        TransactionTemplate(tm).apply { isolationLevel = isolation }

    const val READ_COMMITTED = TransactionDefinition.ISOLATION_READ_COMMITTED
    const val REPEATABLE_READ = TransactionDefinition.ISOLATION_REPEATABLE_READ
}
```

- [ ] **Step 4: Write failing test `ReservationDaoIT.kt`**

```kotlin
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
        // total 3000, 풀 상한 1000 -> 풀은 1000행만 구현
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
        dao.seed(itemId = 501, locationId = 1, ledgerTotal = 2, poolCap = 1000) // 풀 2행
        assertEquals(2, dao.poolSize(501, 1))
        dao.reserve(rc, 501, 1, 1)
        dao.reserve(rc, 501, 1, 1)
        // 풀 소진 -> null (SOLD_OUT)
        assertEquals(null, dao.reserve(rc, 501, 1, 1))
    }
}
```

- [ ] **Step 5: Run to verify FAIL**

Run: `./gradlew test --tests poc.experiment.ReservationDaoIT`
Expected: compile failure (ReservationDao 미정의).

- [ ] **Step 6: ReservationDao.kt 작성**

```kotlin
package poc.experiment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import poc.domain.ReservationId
import poc.domain.SHOP_ID

/**
 * 격리수준을 외부(TransactionTemplate)에서 주입받는 reserve/claim DAO.
 * SQL은 Phase 1 MySqlSkipLockedStrategy와 동일하되, 격리수준을 바꿔가며 실험하기 위해 분리했다.
 */
@Component
class ReservationDao(private val jdbc: JdbcTemplate) {

    /** 테이블 초기화 후, 원장 total을 세팅하고 풀을 min(total, poolCap) 행만 구현한다. */
    fun seed(itemId: Long, locationId: Long, ledgerTotal: Int, poolCap: Int) {
        jdbc.update("DELETE FROM reserved_quantities")
        jdbc.update("DELETE FROM reservation_units")
        jdbc.update("DELETE FROM inventory_ledger")
        jdbc.update(
            "INSERT INTO inventory_ledger(shop_id,item_id,location_id,total_quantity,sold_quantity) VALUES (?,?,?,?,0)",
            SHOP_ID, itemId, locationId, ledgerTotal.toLong(),
        )
        val initial = minOf(ledgerTotal, poolCap)
        if (initial > 0) {
            jdbc.batchUpdate(
                "INSERT INTO reservation_units(shop_id,item_id,location_id,id) VALUES (?,?,?,?)",
                (1..initial).map { arrayOf<Any>(SHOP_ID, itemId, locationId, it.toLong()) },
            )
        }
    }

    /** 성공 시 reservationId, 풀이 비어 SOLD_OUT이면 null. 주어진 tx의 격리수준으로 실행. */
    fun reserve(tx: TransactionTemplate, itemId: Long, locationId: Long, qty: Int): String? =
        tx.execute {
            val unitIds = jdbc.queryForList(
                """SELECT id FROM reservation_units
                   WHERE shop_id=? AND item_id=? AND location_id=?
                   LIMIT ? FOR UPDATE SKIP LOCKED""",
                Long::class.java, SHOP_ID, itemId, locationId, qty,
            )
            if (unitIds.size < qty) {
                it.setRollbackOnly()
                return@execute null
            }
            val reservationId = ReservationId.create(itemId, locationId)
            for (unitId in unitIds) {
                jdbc.update(
                    "DELETE FROM reservation_units WHERE shop_id=? AND item_id=? AND location_id=? AND id=?",
                    SHOP_ID, itemId, locationId, unitId,
                )
                jdbc.update(
                    "INSERT INTO reserved_quantities(reservation_id,shop_id,item_id,location_id,unit_id) VALUES (?,?,?,?,?)",
                    reservationId, SHOP_ID, itemId, locationId, unitId,
                )
            }
            reservationId
        }

    fun claim(tx: TransactionTemplate, reservationId: String) {
        val p = ReservationId.parse(reservationId)
        tx.executeWithoutResult {
            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reserved_quantities WHERE reservation_id=?", Long::class.java, reservationId,
            ) ?: 0
            if (count == 0L) return@executeWithoutResult
            jdbc.update(
                "UPDATE inventory_ledger SET sold_quantity=sold_quantity+? WHERE shop_id=? AND item_id=? AND location_id=?",
                count, SHOP_ID, p.itemId, p.locationId,
            )
            jdbc.update("DELETE FROM reserved_quantities WHERE reservation_id=?", reservationId)
        }
    }

    fun poolSize(itemId: Long, locationId: Long): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation_units WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0

    fun ledgerSnapshot(itemId: Long, locationId: Long): LedgerSnapshot {
        val total = jdbc.queryForObject(
            "SELECT total_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        val sold = jdbc.queryForObject(
            "SELECT sold_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        val reserved = jdbc.queryForObject(
            "SELECT COUNT(*) FROM reserved_quantities WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        return LedgerSnapshot(total = total, sold = sold, reserved = reserved, pool = poolSize(itemId, locationId))
    }
}
```

- [ ] **Step 7: Run to verify PASS**

Run: `./gradlew test --tests poc.experiment.ReservationDaoIT`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add docker-compose.yml src/main/kotlin/poc/experiment/ExperimentConfig.kt src/main/kotlin/poc/experiment/ReservationDao.kt src/test/kotlin/poc/experiment/ReservationDaoIT.kt
git -c user.email="daewook.shin@bucketplace.net" -c user.name="daewook-shin" commit -m "feat: 실험용 ReservationDao + 격리수준 템플릿 + lock-wait timeout 단축"
```
(커밋 메시지 끝에 빈 줄 후 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` 트레일러 포함)

---

### Task 2: ReplenishmentJob

**Files:**
- Create: `src/main/kotlin/poc/experiment/ReplenishmentJob.kt`
- Test: `src/test/kotlin/poc/experiment/ReplenishmentJobIT.kt`

- [ ] **Step 1: Write failing test `ReplenishmentJobIT.kt`**

```kotlin
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
        // total 3000, 풀 상한 1000 -> 1000행 구현됨
        dao.seed(itemId = 600, locationId = 1, ledgerTotal = 3000, poolCap = 1000)
        // 풀에서 400건 reserve+claim 하여 풀을 600으로 떨어뜨림 (claim으로 sold=400)
        repeat(400) {
            val rid = dao.reserve(rc, 600, 1, 1)!!
            dao.claim(rc, rid)
        }
        assertEquals(600, dao.poolSize(600, 1))

        // 보충: 상한 1000까지 채워야 하고, unmaterialized(3000-400-0-600=2000)이 충분하므로 400행 INSERT
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
        // total 1200, 상한 1000 -> 풀 1000. 300 claim -> 풀 700, sold 300, unmaterialized=1200-300-0-700=200
        dao.seed(itemId = 602, locationId = 1, ledgerTotal = 1200, poolCap = 1000)
        repeat(300) { val rid = dao.reserve(rc, 602, 1, 1)!!; dao.claim(rc, rid) }
        assertEquals(700, dao.poolSize(602, 1))
        // 상한까지면 300이지만 unmaterialized가 200뿐 -> 200만 INSERT
        assertEquals(200, job.replenishOnce(rc, 602, 1, poolCap = 1000))
        assertEquals(900, dao.poolSize(602, 1))
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./gradlew test --tests poc.experiment.ReplenishmentJobIT`
Expected: compile failure (ReplenishmentJob 미정의).

- [ ] **Step 3: ReplenishmentJob.kt 작성**

```kotlin
package poc.experiment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import poc.domain.SHOP_ID

/**
 * bounded pool(poolCap)을 원장 기준으로 보충한다.
 * deficit = min(poolCap - pool, unmaterialized), unmaterialized = total - sold - reserved - pool.
 * 새 unit id는 현재 살아있는 행(units + reserved)의 최대 id+1부터 연속 배정해 PK 충돌을 피한다.
 */
@Component
class ReplenishmentJob(private val jdbc: JdbcTemplate) {

    /** 주어진 tx로 1회 보충. INSERT한 행 수를 반환. */
    fun replenishOnce(tx: TransactionTemplate, itemId: Long, locationId: Long, poolCap: Int): Int =
        tx.execute {
            val total = jdbc.queryForObject(
                "SELECT total_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
                Long::class.java, SHOP_ID, itemId, locationId,
            ) ?: 0
            val sold = jdbc.queryForObject(
                "SELECT sold_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
                Long::class.java, SHOP_ID, itemId, locationId,
            ) ?: 0
            val reserved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reserved_quantities WHERE shop_id=? AND item_id=? AND location_id=?",
                Long::class.java, SHOP_ID, itemId, locationId,
            ) ?: 0
            val pool = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_units WHERE shop_id=? AND item_id=? AND location_id=?",
                Long::class.java, SHOP_ID, itemId, locationId,
            ) ?: 0
            val unmaterialized = total - sold - reserved - pool
            val deficit = minOf(poolCap.toLong() - pool, unmaterialized).coerceAtLeast(0).toInt()
            if (deficit == 0) return@execute 0

            val base = jdbc.queryForObject(
                """SELECT COALESCE(MAX(id),0) FROM (
                     SELECT id FROM reservation_units WHERE shop_id=? AND item_id=? AND location_id=?
                     UNION ALL
                     SELECT unit_id AS id FROM reserved_quantities WHERE shop_id=? AND item_id=? AND location_id=?
                   ) t""",
                Long::class.java,
                SHOP_ID, itemId, locationId, SHOP_ID, itemId, locationId,
            ) ?: 0
            jdbc.batchUpdate(
                "INSERT INTO reservation_units(shop_id,item_id,location_id,id) VALUES (?,?,?,?)",
                (1..deficit).map { arrayOf<Any>(SHOP_ID, itemId, locationId, base + it) },
            )
            deficit
        } ?: 0
}
```

- [ ] **Step 4: Run to verify PASS**

Run: `./gradlew test --tests poc.experiment.ReplenishmentJobIT`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/poc/experiment/ReplenishmentJob.kt src/test/kotlin/poc/experiment/ReplenishmentJobIT.kt
git -c user.email="daewook.shin@bucketplace.net" -c user.name="daewook-shin" commit -m "feat: bounded pool ReplenishmentJob"
```
(Co-Authored-By 트레일러 포함)

---

### Task 3: ExperimentRunner — E2 (bounded pool 정합성)

**Files:**
- Create: `src/main/kotlin/poc/experiment/ExperimentRunner.kt`
- Test: `src/test/kotlin/poc/experiment/ExperimentE2IT.kt`

- [ ] **Step 1: Write failing test `ExperimentE2IT.kt`**

```kotlin
package poc.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class ExperimentE2IT @Autowired constructor(
    val runner: ExperimentRunner,
) {
    @Test
    fun `E2 bounded pool never oversells and pool stays within cap`() {
        // total 5000, poolCap 1000, 충분한 수요로 풀이 반복 소진/보충됨
        val report = runner.runE2(clients = 100, rounds = 20, ledgerTotal = 5000, poolCap = 1000)
        assertEquals(0, report.oversell)                       // oversell 0
        assertTrue(report.maxPoolObserved <= 1000, "pool exceeded cap: ${report.maxPoolObserved}")
        assertTrue(report.conserved, "conservation violated")
        assertTrue(report.successes <= 5000, "claimed more than total: ${report.successes}")
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./gradlew test --tests poc.experiment.ExperimentE2IT`
Expected: compile failure (ExperimentRunner / runE2 미정의).

- [ ] **Step 3: ExperimentRunner.kt 작성 (E2 + 공용 분류 로직)**

```kotlin
package poc.experiment

import kotlinx.coroutines.CoroutineScope
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
import org.springframework.transaction.support.TransactionTemplate

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
}
```

- [ ] **Step 4: Run to verify PASS**

Run: `./gradlew test --tests poc.experiment.ExperimentE2IT`
Expected: PASS. oversell=0, maxPoolObserved ≤ 1000, conserved=true.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/poc/experiment/ExperimentRunner.kt src/test/kotlin/poc/experiment/ExperimentE2IT.kt
git -c user.email="daewook.shin@bucketplace.net" -c user.name="daewook-shin" commit -m "feat: ExperimentRunner E2 (bounded pool 정합성)"
```
(Co-Authored-By 트레일러 포함)

---

### Task 4: ExperimentRunner — E1 (격리수준 데드락)

**Files:**
- Modify: `src/main/kotlin/poc/experiment/ExperimentRunner.kt` (runE1 추가)
- Test: `src/test/kotlin/poc/experiment/ExperimentE1IT.kt`

- [ ] **Step 1: Write failing test `ExperimentE1IT.kt`**

> 깨끗한 경로(READ COMMITTED)만 단언한다. REPEATABLE READ의 데드락 발생은 확률적이라 단언하지 않고 results 문서에 기록(Task 6).

```kotlin
package poc.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class ExperimentE1IT @Autowired constructor(
    val runner: ExperimentRunner,
) {
    @Test
    fun `E1 under READ COMMITTED completes with no lock conflicts`() {
        val stats = runner.runE1(isolation = Tx.READ_COMMITTED, clients = 50, rounds = 10)
        assertEquals(0, stats.deadlocks.get(), "RC should not deadlock")
        assertEquals(0, stats.lockWaitTimeouts.get(), "RC should not time out on locks")
        assertTrue(stats.successes.get() > 0, "expected some successful reservations")
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./gradlew test --tests poc.experiment.ExperimentE1IT`
Expected: compile failure (runE1 미정의).

- [ ] **Step 3: ExperimentRunner.kt 에 runE1 추가**

`ExperimentRunner` 클래스 안, `runE2` 아래에 다음 메서드를 추가한다:

```kotlin
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
```

- [ ] **Step 4: Run to verify PASS**

Run: `./gradlew test --tests poc.experiment.ExperimentE1IT`
Expected: PASS. READ COMMITTED 경로에서 deadlocks=0, lockWaitTimeouts=0, successes>0.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/poc/experiment/ExperimentRunner.kt src/test/kotlin/poc/experiment/ExperimentE1IT.kt
git -c user.email="daewook.shin@bucketplace.net" -c user.name="daewook-shin" commit -m "feat: ExperimentRunner E1 (격리수준 데드락 재현)"
```
(Co-Authored-By 트레일러 포함)

---

### Task 5: CLI 분기 + 결과 출력

**Files:**
- Modify: `src/main/kotlin/poc/BenchmarkCommandLineRunner.kt`
- Create: `src/main/kotlin/poc/experiment/ExperimentPrinter.kt`
- Test: `src/test/kotlin/poc/experiment/ExperimentPrinterTest.kt`

- [ ] **Step 1: Write failing test `ExperimentPrinterTest.kt`**

```kotlin
package poc.experiment

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExperimentPrinterTest {
    @Test
    fun `E1 table shows isolation rows and conflict columns`() {
        val rr = DeadlockStats().apply { deadlocks.set(3); lockWaitTimeouts.set(5); successes.set(120) }
        val rc = DeadlockStats().apply { successes.set(500) }
        val md = ExperimentPrinter.e1Markdown(rrStats = rr, rcStats = rc)
        assertTrue(md.contains("REPEATABLE_READ"))
        assertTrue(md.contains("READ_COMMITTED"))
        assertTrue(md.contains("deadlocks"))
    }

    @Test
    fun `E2 table shows oversell and pool cap`() {
        val report = E2Report(
            total = 5000, successes = 5000, oversell = 0, conserved = true,
            maxPoolObserved = 1000,
            finalSnapshot = LedgerSnapshot(5000, 5000, 0, 0),
        )
        val md = ExperimentPrinter.e2Markdown(report)
        assertTrue(md.contains("oversell"))
        assertTrue(md.contains("maxPool"))
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./gradlew test --tests poc.experiment.ExperimentPrinterTest`
Expected: compile failure (ExperimentPrinter 미정의).

- [ ] **Step 3: ExperimentPrinter.kt 작성**

```kotlin
package poc.experiment

object ExperimentPrinter {
    fun e1Markdown(rrStats: DeadlockStats, rcStats: DeadlockStats): String {
        val sb = StringBuilder()
        sb.appendLine("### E1 — 격리수준별 락 충돌")
        sb.appendLine("| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |")
        sb.appendLine("|---|---|---|---|---|---|")
        fun row(name: String, s: DeadlockStats) =
            "| $name | ${s.deadlocks.get()} | ${s.lockWaitTimeouts.get()} | ${s.otherErrors.get()} | ${s.successes.get()} | ${s.soldOut.get()} |"
        sb.appendLine(row("REPEATABLE_READ", rrStats))
        sb.appendLine(row("READ_COMMITTED", rcStats))
        return sb.toString()
    }

    fun e2Markdown(r: E2Report): String {
        val sb = StringBuilder()
        sb.appendLine("### E2 — Bounded pool 정합성")
        sb.appendLine("| total | successes(sold) | oversell | conserved | maxPoolObserved | finalPool |")
        sb.appendLine("|---|---|---|---|---|---|")
        sb.appendLine("| ${r.total} | ${r.successes} | ${r.oversell} | ${r.conserved} | ${r.maxPoolObserved} | ${r.finalSnapshot.pool} |")
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run to verify PASS**

Run: `./gradlew test --tests poc.experiment.ExperimentPrinterTest`
Expected: PASS (2 tests).

- [ ] **Step 5: BenchmarkCommandLineRunner.kt 에 --experiment 분기 추가**

기존 `run(args)` 본문 최상단(옵션 파싱 직후, 벤치마크 루프 이전)에 분기를 넣고, ExperimentRunner를 주입받는다. 변경 후 전체 파일은 다음과 같다:

```kotlin
package poc

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import poc.bench.BenchConfig
import poc.bench.BenchmarkRunner
import poc.bench.BenchResult
import poc.bench.ResultPrinter
import poc.bench.Scenario
import poc.domain.ReservationStrategy
import poc.experiment.ExperimentPrinter
import poc.experiment.ExperimentRunner
import poc.experiment.Tx

@Component
@Profile("!test")
class BenchmarkCommandLineRunner(
    private val strategies: List<ReservationStrategy>,
    private val experimentRunner: ExperimentRunner,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val clients = args.getOptionValues("clients")?.firstOrNull()?.toInt() ?: 200

        // --experiment=E1|E2|all 이면 Phase 2 실험을 돌리고 종료
        val experiment = args.getOptionValues("experiment")?.firstOrNull()
        if (experiment != null) {
            runExperiments(experiment, clients)
            context.close()
            return
        }

        // 기존 Phase 1 벤치마크
        val strategyFilter = args.getOptionValues("strategy")?.firstOrNull()
        val scenarioFilter = args.getOptionValues("scenario")?.firstOrNull()
        val selectedStrategies = strategies.filter { strategyFilter == null || it.name() == strategyFilter }
        val selectedScenarios = Scenario.entries.filter { scenarioFilter == null || it.name == scenarioFilter }

        val runner = BenchmarkRunner(BenchConfig(clients = clients))
        val results = mutableListOf<BenchResult>()
        for (strategy in selectedStrategies) {
            for (scenario in selectedScenarios) {
                println(">>> ${strategy.name()} / ${scenario.name} (clients=$clients) ...")
                results.add(runner.run(strategy, scenario))
            }
        }
        println("\n## 결과\n")
        println(ResultPrinter.toMarkdown(results))
        context.close()
    }

    private fun runExperiments(which: String, clients: Int) {
        if (which == "E1" || which == "all") {
            println(">>> E1 격리수준 데드락 (REPEATABLE_READ) ...")
            val rr = experimentRunner.runE1(Tx.REPEATABLE_READ, clients = clients, rounds = 10)
            println(">>> E1 격리수준 데드락 (READ_COMMITTED) ...")
            val rc = experimentRunner.runE1(Tx.READ_COMMITTED, clients = clients, rounds = 10)
            println("\n${ExperimentPrinter.e1Markdown(rrStats = rr, rcStats = rc)}")
        }
        if (which == "E2" || which == "all") {
            println(">>> E2 bounded pool 정합성 ...")
            val r = experimentRunner.runE2(clients = clients, rounds = 20, ledgerTotal = 5000, poolCap = 1000)
            println("\n${ExperimentPrinter.e2Markdown(r)}")
        }
    }
}
```

- [ ] **Step 6: Smoke test the experiment CLI**

Run: `./gradlew bootRun --args='--experiment=E2 --clients=50' 2>/dev/null`
Expected: `>>> E2 bounded pool 정합성 ...` 와 E2 마크다운 표(oversell 0) 출력 후 깨끗하게 종료.
- 그 다음 전체 테스트가 여전히 통과하는지: `./gradlew test 2>&1 | tail -5` → BUILD SUCCESSFUL (실험 러너가 @Profile("!test")라 테스트 컨텍스트에서 안 돈다).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/poc/BenchmarkCommandLineRunner.kt src/main/kotlin/poc/experiment/ExperimentPrinter.kt src/test/kotlin/poc/experiment/ExperimentPrinterTest.kt
git -c user.email="daewook.shin@bucketplace.net" -c user.name="daewook-shin" commit -m "feat: CLI --experiment 분기 + 실험 결과 리포트"
```
(Co-Authored-By 트레일러 포함)

---

### Task 6: 전체 실험 실행 + 결과 분석 문서

**Files:**
- Create: `docs/results/2026-06-04-phase2-results.md`

> 실제 캡처한 수치만 기록한다. 절대 지어내지 않는다.

- [ ] **Step 1: 인프라 확인**

Run: `docker compose ps`
Expected: mysql healthy (lock-wait timeout 3초 설정 적용된 상태). 아니면 `docker compose up -d`.

- [ ] **Step 2: E1 실행 (격리수준 데드락)**

Run: `./gradlew bootRun --args='--experiment=E1 --clients=100' 2>/dev/null`
- REPEATABLE_READ 행과 READ_COMMITTED 행의 deadlocks/lockWaitTimeouts/successes 캡처.
- 기대(검증, 강제 금지): RR에서 deadlocks+lockWaitTimeouts > 0, RC에서 0. 실제 결과가 다르면 그대로 기록.
- RR에서 충돌이 0이면 1-2회 더 실행해 본다(스케줄러 의존). 그래도 0이면 clients를 150~200으로 올려 재시도하고, 관찰된 수치를 정직하게 기록.

- [ ] **Step 3: E2 실행 (bounded pool)**

Run: `./gradlew bootRun --args='--experiment=E2 --clients=100' 2>/dev/null`
- total/successes/oversell/conserved/maxPoolObserved/finalPool 캡처. 기대: oversell 0, maxPool ≤ 1000, conserved true.

- [ ] **Step 4: 결과 문서 작성 `docs/results/2026-06-04-phase2-results.md`**

다음을 포함:
1. **실행 환경**: macOS, Docker mysql:8.0 (`--innodb-lock-wait-timeout=3`), Java 17, clients 등 설정.
2. **E1 결과표**: Step 2의 실제 격리수준별 충돌 표 붙여넣기.
3. **E1 해석**: REPEATABLE READ에서 `SELECT … FOR UPDATE SKIP LOCKED`가 supremum 의사레코드에 gap lock을 잡아, 더 높은 id를 INSERT하는 replenishment와 충돌 → 데드락/타임아웃. READ COMMITTED는 gap lock을 잡지 않아 깨끗. (Shopify 글이 REPEATABLE READ→READ COMMITTED로 바꾼 이유와 일치.) 만약 RR에서 충돌이 관찰되지 않았다면, 그 사실과 가능한 원인(스케줄링 타이밍, 부하 부족)을 정직하게 기술.
4. **E2 결과표 + 해석**: Step 3 실제 수치. 풀이 1000 상한 안에서 소진·보충을 반복하면서도 oversell 0·보존 유지 → bounded pool 모델이 정합성을 깨지 않음.
5. **한계**: 로컬 단일 머신, 데드락의 스케줄러 의존 확률성, lock-wait timeout 3초로 인위 단축, E3(lock ordering) 미구현(스펙에 선택 항목으로 남김).

- [ ] **Step 5: Commit**

```bash
git add docs/results/2026-06-04-phase2-results.md
git -c user.email="daewook.shin@bucketplace.net" -c user.name="daewook-shin" commit -m "docs: Phase 2 실험 결과 + 격리수준/bounded pool 분석"
```
(Co-Authored-By 트레일러 포함)

---

## Self-Review

**Spec coverage 체크 (스펙 → 태스크 매핑):**
- §2 구조(poc.experiment: ReservationDao/ReplenishmentJob/ExperimentRunner) → Task 1/2/3·4 ✓
- §3 데드락 감지(1213/1205 분류) → Task 3 `classify` + Task 5 프린터 ✓
- §4 E1(total 3000, pool 1000, RR vs RC) → Task 4 ✓
- §4 E2(total 5000, pool 1000, 결정적 검증) → Task 3 ✓
- §4 E3 → **선택 항목, 의도적으로 plan 제외** (스펙 §8과 일치) ✓
- §5 테스트 전략(E2 단언, E1 깨끗한 경로만 단언) → Task 3 단언 + Task 4 RC-only 단언 ✓
- §6 CLI(--experiment 분기, 단일 진입점) → Task 5 ✓
- §7 산출물(results 문서) → Task 6 ✓
- §8 YAGNI 제외(복합PK 실험/ProxySQL/E3) → plan에 없음 ✓

**Placeholder scan:** 없음. 모든 코드 스텝에 완전한 코드 포함.

**Type consistency 체크:**
- `ReservationDao`: seed(itemId,locationId,ledgerTotal,poolCap)/reserve(tx,…):String?/claim(tx,id)/poolSize/ledgerSnapshot — Task 1 정의가 Task 2·3·4 사용처와 일치 ✓
- `ReplenishmentJob.replenishOnce(tx,itemId,locationId,poolCap):Int` — Task 2 정의가 Task 3·4 호출과 일치 ✓
- `DeadlockStats`(deadlocks/lockWaitTimeouts/otherErrors/successes/soldOut: AtomicLong), `LedgerSnapshot`(total/sold/reserved/pool + oversell/conserved), `Tx`(template/READ_COMMITTED/REPEATABLE_READ) — Task 1 정의가 전 태스크에서 일관 ✓
- `E2Report`(total/successes/oversell/conserved/maxPoolObserved/finalSnapshot) — Task 3 정의가 Task 5 프린터·테스트와 일치 ✓
- `ExperimentRunner`(runE2, runE1, classify) — Task 3에서 클래스+runE2 생성, Task 4에서 runE1 추가, Task 5에서 주입·호출 일치 ✓
- `BenchmarkCommandLineRunner` 생성자에 `experimentRunner` 추가 — Task 5에서 반영, @Profile("!test") 유지 ✓
