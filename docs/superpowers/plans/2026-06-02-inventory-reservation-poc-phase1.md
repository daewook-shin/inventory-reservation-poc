# 재고 예약 3-way 비교 PoC — Phase 1 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MySQL SKIP LOCKED · 순수 Redis · Redis+MySQL 분리 세 가지 재고 예약 전략을 동일 부하 하니스로 돌려 정합성과 처리량을 비교한다.

**Architecture:** 단일 Spring Boot(Kotlin) 콘솔 앱. `ReservationStrategy` 인터페이스에 세 구현체를 두고, 코루틴 기반 부하 생성기가 동일 시나리오로 각 전략을 측정한다. 부하는 HTTP를 거치지 않고 서비스 계층을 직접 호출한다. 정합성은 `CorrectnessOracle`가, 성능은 per-coroutine 수집 후 병합으로 집계한다.

**Tech Stack:** Kotlin, Spring Boot 3.4 (`spring-boot-starter-jdbc`), JDK 21, kotlinx-coroutines, MySQL 8.0(`FOR UPDATE SKIP LOCKED`), Redis 6(Jedis), Flyway, Gradle Kotlin DSL, JUnit5. 인프라는 docker-compose(`mysql:8.0` + `redis:6-alpine`).

> **공통 전제 (전략 통합 테스트):** 전략 ①·②·③의 통합 테스트는 `docker compose up -d`로 MySQL(localhost:3306)·Redis(localhost:6379)가 떠 있어야 한다. 순수 로직 테스트(MetricsCollector, percentile, Oracle)는 인프라 불필요.

> **reservationId 규약:** 모든 전략에서 `reservationId = "{itemId}:{locationId}:{uuid}"` 형식. claim/release가 id만으로 대상 키를 알 수 있게 한다. 파싱 헬퍼는 Task 3에서 정의.

---

### Task 1: Gradle + Spring Boot 스캐폴드

**Files:**
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `src/main/kotlin/poc/InventoryReservationPocApplication.kt`
- Create: `src/main/resources/application.yml`

- [ ] **Step 1: settings.gradle.kts**

```kotlin
rootProject.name = "inventory-reservation-poc"
```

- [ ] **Step 2: gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2g
kotlin.code.style=official
```

- [ ] **Step 3: build.gradle.kts**

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "poc"
version = "0.1.0"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("redis.clients:jedis:5.2.0")
    implementation("org.flywaydb:flyway-core:11.1.0")
    implementation("org.flywaydb:flyway-mysql:11.1.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 4: application.yml**

```yaml
spring:
  main:
    web-application-type: none
  datasource:
    url: jdbc:mysql://localhost:3306/reservation_poc?useSSL=false&allowPublicKeyRetrieval=true
    username: poc
    password: poc
    hikari:
      maximum-pool-size: ${POOL_SIZE:50}
      connection-timeout: 10000
  flyway:
    enabled: true
    baseline-on-migrate: true

redis:
  host: localhost
  port: 6379
```

- [ ] **Step 5: InventoryReservationPocApplication.kt**

```kotlin
package poc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class InventoryReservationPocApplication

fun main(args: Array<String>) {
    runApplication<InventoryReservationPocApplication>(*args)
}
```

- [ ] **Step 6: Build to verify scaffold compiles**

Run: `./gradlew build -x test`
Expected: `BUILD SUCCESSFUL` (gradle wrapper가 없으면 `gradle wrapper` 먼저 실행)

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts settings.gradle.kts gradle.properties src/ gradlew gradlew.bat gradle/
git commit -m "chore: Spring Boot Kotlin 스캐폴드"
```

---

### Task 2: docker-compose + Flyway 스키마

**Files:**
- Create: `docker-compose.yml`
- Create: `src/main/resources/db/migration/V1__schema.sql`

- [ ] **Step 1: docker-compose.yml**

```yaml
services:
  mysql:
    image: public.ecr.aws/docker/library/mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: reservation_poc
      MYSQL_USER: poc
      MYSQL_PASSWORD: poc
    command: ["--transaction-isolation=READ-COMMITTED", "--innodb-thread-concurrency=0"]
    ports: ["3306:3306"]
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot"]
      interval: 3s
      timeout: 3s
      retries: 20
  redis:
    image: public.ecr.aws/docker/library/redis:6-alpine
    ports: ["6379:6379"]
```

- [ ] **Step 2: V1__schema.sql**

```sql
CREATE TABLE reservation_units (
    shop_id     BIGINT NOT NULL,
    item_id     BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    id          BIGINT NOT NULL,
    PRIMARY KEY (shop_id, item_id, location_id, id)
) ENGINE = InnoDB;

CREATE TABLE reserved_quantities (
    reservation_id VARCHAR(96) NOT NULL,
    shop_id        BIGINT NOT NULL,
    item_id        BIGINT NOT NULL,
    location_id    BIGINT NOT NULL,
    unit_id        BIGINT NOT NULL,
    PRIMARY KEY (reservation_id, unit_id)
) ENGINE = InnoDB;

CREATE TABLE inventory_ledger (
    shop_id        BIGINT NOT NULL,
    item_id        BIGINT NOT NULL,
    location_id    BIGINT NOT NULL,
    total_quantity BIGINT NOT NULL,
    sold_quantity  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (shop_id, item_id, location_id)
) ENGINE = InnoDB;
```

- [ ] **Step 3: Start infra and verify migration runs**

Run: `docker compose up -d && ./gradlew bootRun` (앱이 떠서 Flyway 마이그레이션 적용 후 Ctrl+C)
Expected: 로그에 `Successfully applied 1 migration`; `docker compose exec mysql mysql -upoc -ppoc reservation_poc -e "SHOW TABLES;"` 가 3개 테이블 출력

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml src/main/resources/db/migration/V1__schema.sql
git commit -m "feat: docker-compose 인프라 + Flyway 스키마"
```

---

### Task 3: 도메인 모델 + 전략 인터페이스

**Files:**
- Create: `src/main/kotlin/poc/domain/Model.kt`
- Create: `src/main/kotlin/poc/domain/ReservationStrategy.kt`
- Test: `src/test/kotlin/poc/domain/ReservationIdTest.kt`

- [ ] **Step 1: Write failing test for reservationId 인코딩**

```kotlin
package poc.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReservationIdTest {
    @Test
    fun `encode then parse roundtrips item and location`() {
        val id = ReservationId.create(itemId = 7, locationId = 3)
        val parsed = ReservationId.parse(id)
        assertEquals(7L, parsed.itemId)
        assertEquals(3L, parsed.locationId)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests poc.domain.ReservationIdTest`
Expected: FAIL — `ReservationId` 미정의 컴파일 에러

- [ ] **Step 3: Model.kt 작성**

```kotlin
package poc.domain

import java.util.UUID

const val SHOP_ID = 1L

enum class ReservationOutcome { SUCCESS, SOLD_OUT }

data class ReservationResult(val outcome: ReservationOutcome, val reservationId: String?)

data class InventorySnapshot(val sold: Long, val reserved: Long, val available: Long)

data class ParsedId(val itemId: Long, val locationId: Long)

object ReservationId {
    fun create(itemId: Long, locationId: Long): String =
        "$itemId:$locationId:${UUID.randomUUID()}"

    fun parse(id: String): ParsedId {
        val parts = id.split(":")
        return ParsedId(parts[0].toLong(), parts[1].toLong())
    }
}
```

- [ ] **Step 4: ReservationStrategy.kt 작성**

```kotlin
package poc.domain

interface ReservationStrategy {
    fun name(): String
    /** 이 전략의 모든 상태를 비우고 주어진 아이템들을 초기 재고로 세팅한다. */
    fun reset(items: List<ItemSpec>)
    fun reserve(itemId: Long, locationId: Long, qty: Int): ReservationResult
    fun claim(reservationId: String)
    fun release(reservationId: String)
    fun snapshot(itemId: Long, locationId: Long): InventorySnapshot
}

data class ItemSpec(val itemId: Long, val locationId: Long, val totalStock: Int)
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew test --tests poc.domain.ReservationIdTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/poc/domain/ src/test/kotlin/poc/domain/ReservationIdTest.kt
git commit -m "feat: 도메인 모델 + ReservationStrategy 인터페이스"
```

---

### Task 4: MetricsCollector + CorrectnessOracle

**Files:**
- Create: `src/main/kotlin/poc/bench/Metrics.kt`
- Create: `src/main/kotlin/poc/bench/CorrectnessOracle.kt`
- Test: `src/test/kotlin/poc/bench/MetricsTest.kt`
- Test: `src/test/kotlin/poc/bench/CorrectnessOracleTest.kt`

- [ ] **Step 1: Write failing test for percentile + oracle**

```kotlin
package poc.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import poc.domain.InventorySnapshot

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
```

```kotlin
package poc.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import poc.domain.InventorySnapshot

class CorrectnessOracleTest {
    @Test
    fun `conserved snapshot has zero oversell`() {
        val report = CorrectnessOracle.check(InventorySnapshot(sold = 4, reserved = 2, available = 4), total = 10)
        assertTrue(report.conserved)
        assertEquals(0, report.oversell)
    }

    @Test
    fun `oversell detected when sold plus reserved exceeds total`() {
        val report = CorrectnessOracle.check(InventorySnapshot(sold = 8, reserved = 5, available = 0), total = 10)
        assertFalse(report.conserved)
        assertEquals(3, report.oversell)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "poc.bench.*"`
Expected: FAIL — 미정의 심볼 컴파일 에러

- [ ] **Step 3: Metrics.kt 작성**

```kotlin
package poc.bench

data class ClientResult(
    var success: Long = 0,
    var soldOut: Long = 0,
    var errors: Long = 0,
    val latenciesNs: MutableList<Long> = mutableListOf(),
)

data class LatencyStats(val p50: Long, val p95: Long, val p99: Long, val count: Int) {
    companion object {
        fun from(latenciesNs: List<Long>): LatencyStats {
            if (latenciesNs.isEmpty()) return LatencyStats(0, 0, 0, 0)
            val sorted = latenciesNs.sorted()
            fun pct(p: Double): Long {
                val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
                return sorted[rank - 1]
            }
            return LatencyStats(pct(50.0), pct(95.0), pct(99.0), sorted.size)
        }
    }
}

fun List<ClientResult>.merge(): ClientResult {
    val out = ClientResult()
    for (r in this) {
        out.success += r.success
        out.soldOut += r.soldOut
        out.errors += r.errors
        out.latenciesNs.addAll(r.latenciesNs)
    }
    return out
}
```

- [ ] **Step 4: CorrectnessOracle.kt 작성**

```kotlin
package poc.bench

import poc.domain.InventorySnapshot

data class OracleReport(val conserved: Boolean, val oversell: Long, val available: Long, val soldOut: Boolean = false)

object CorrectnessOracle {
    /** 보존 법칙: sold + reserved + available == total. 초과분(oversell)은 0 이상. */
    fun check(snap: InventorySnapshot, total: Long): OracleReport {
        val accounted = snap.sold + snap.reserved + snap.available
        val oversell = (snap.sold + snap.reserved - total).coerceAtLeast(0)
        return OracleReport(conserved = accounted == total, oversell = oversell, available = snap.available)
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew test --tests "poc.bench.*"`
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/poc/bench/ src/test/kotlin/poc/bench/
git commit -m "feat: MetricsCollector + CorrectnessOracle"
```

---

### Task 5: 전략 ① MySqlSkipLockedStrategy

**Files:**
- Create: `src/main/kotlin/poc/strategy/MySqlSkipLockedStrategy.kt`
- Create: `src/main/kotlin/poc/config/JdbcConfig.kt`
- Test: `src/test/kotlin/poc/strategy/MySqlSkipLockedStrategyIT.kt`

> 통합 테스트는 `docker compose up -d` 필요.

- [ ] **Step 1: Write failing integration test**

```kotlin
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `docker compose up -d && ./gradlew test --tests poc.strategy.MySqlSkipLockedStrategyIT`
Expected: FAIL — `MySqlSkipLockedStrategy` 미정의

- [ ] **Step 3: JdbcConfig.kt 작성 (TransactionTemplate READ_COMMITTED)**

```kotlin
package poc.config

import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class JdbcConfig {
    @Bean
    fun readCommittedTxTemplate(ds: DataSource): TransactionTemplate {
        val tm = DataSourceTransactionManager(ds)
        val t = TransactionTemplate(tm)
        t.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        return t
    }
}
```

- [ ] **Step 4: MySqlSkipLockedStrategy.kt 작성**

```kotlin
package poc.strategy

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import poc.domain.InventorySnapshot
import poc.domain.ItemSpec
import poc.domain.ReservationId
import poc.domain.ReservationOutcome
import poc.domain.ReservationResult
import poc.domain.ReservationStrategy
import poc.domain.SHOP_ID

@Component
class MySqlSkipLockedStrategy(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
) : ReservationStrategy {

    override fun name() = "mysql-skip-locked"

    override fun reset(items: List<ItemSpec>) {
        jdbc.update("DELETE FROM reserved_quantities")
        jdbc.update("DELETE FROM reservation_units")
        jdbc.update("DELETE FROM inventory_ledger")
        for (item in items) {
            jdbc.batchUpdate(
                "INSERT INTO reservation_units(shop_id,item_id,location_id,id) VALUES (?,?,?,?)",
                (1..item.totalStock).map { arrayOf<Any>(SHOP_ID, item.itemId, item.locationId, it.toLong()) },
            )
            jdbc.update(
                "INSERT INTO inventory_ledger(shop_id,item_id,location_id,total_quantity,sold_quantity) VALUES (?,?,?,?,0)",
                SHOP_ID, item.itemId, item.locationId, item.totalStock.toLong(),
            )
        }
    }

    override fun reserve(itemId: Long, locationId: Long, qty: Int): ReservationResult =
        tx.execute {
            // lock ordering: reservation_units 부터 잠근다
            val unitIds = jdbc.queryForList(
                """SELECT id FROM reservation_units
                   WHERE shop_id=? AND item_id=? AND location_id=?
                   FOR UPDATE SKIP LOCKED LIMIT ?""",
                Long::class.java, SHOP_ID, itemId, locationId, qty,
            )
            if (unitIds.size < qty) {
                it.setRollbackOnly()
                return@execute ReservationResult(ReservationOutcome.SOLD_OUT, null)
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
            ReservationResult(ReservationOutcome.SUCCESS, reservationId)
        }!!

    override fun claim(reservationId: String) {
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

    override fun release(reservationId: String) {
        val p = ReservationId.parse(reservationId)
        tx.executeWithoutResult {
            val unitIds = jdbc.queryForList(
                "SELECT unit_id FROM reserved_quantities WHERE reservation_id=?", Long::class.java, reservationId,
            )
            for (unitId in unitIds) {
                jdbc.update(
                    "INSERT INTO reservation_units(shop_id,item_id,location_id,id) VALUES (?,?,?,?)",
                    SHOP_ID, p.itemId, p.locationId, unitId,
                )
            }
            jdbc.update("DELETE FROM reserved_quantities WHERE reservation_id=?", reservationId)
        }
    }

    override fun snapshot(itemId: Long, locationId: Long): InventorySnapshot {
        val available = jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation_units WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        val reserved = jdbc.queryForObject(
            "SELECT COUNT(*) FROM reserved_quantities WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        val sold = jdbc.queryForObject(
            "SELECT sold_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        return InventorySnapshot(sold = sold, reserved = reserved, available = available)
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew test --tests poc.strategy.MySqlSkipLockedStrategyIT`
Expected: PASS (2 tests). 핵심: `concurrent reserve ... never oversells`에서 정확히 10건 성공.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/poc/strategy/MySqlSkipLockedStrategy.kt src/main/kotlin/poc/config/JdbcConfig.kt src/test/kotlin/poc/strategy/MySqlSkipLockedStrategyIT.kt
git commit -m "feat: 전략 ① MySQL SKIP LOCKED (oversell 0 검증)"
```

---

### Task 6: 전략 ② RedisCounterStrategy

**Files:**
- Create: `src/main/kotlin/poc/config/RedisConfig.kt`
- Create: `src/main/kotlin/poc/strategy/RedisCounterStrategy.kt`
- Test: `src/test/kotlin/poc/strategy/RedisCounterStrategyIT.kt`

- [ ] **Step 1: Write failing integration test**

```kotlin
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests poc.strategy.RedisCounterStrategyIT`
Expected: FAIL — 미정의

- [ ] **Step 3: RedisConfig.kt 작성 (JedisPool)**

```kotlin
package poc.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

@Configuration
class RedisConfig {
    @Bean(destroyMethod = "close")
    fun jedisPool(
        @Value("\${redis.host}") host: String,
        @Value("\${redis.port}") port: Int,
    ): JedisPool {
        val cfg = JedisPoolConfig().apply { maxTotal = 256; maxIdle = 64 }
        return JedisPool(cfg, host, port)
    }
}
```

- [ ] **Step 4: RedisCounterStrategy.kt 작성**

```kotlin
package poc.strategy

import org.springframework.stereotype.Component
import poc.domain.InventorySnapshot
import poc.domain.ItemSpec
import poc.domain.ReservationId
import poc.domain.ReservationOutcome
import poc.domain.ReservationResult
import poc.domain.ReservationStrategy
import redis.clients.jedis.JedisPool

@Component
class RedisCounterStrategy(private val pool: JedisPool) : ReservationStrategy {

    override fun name() = "redis-counter"

    private fun stockKey(i: Long, l: Long) = "stock:$i:$l"
    private fun soldKey(i: Long, l: Long) = "sold:$i:$l"
    private fun resKey(i: Long, l: Long) = "res:$i:$l"

    private val reserveLua = """
        local stock = tonumber(redis.call('GET', KEYS[1]))
        local qty = tonumber(ARGV[1])
        if stock == nil or stock < qty then return 0 end
        redis.call('DECRBY', KEYS[1], qty)
        redis.call('HSET', KEYS[2], ARGV[2], qty)
        return 1
    """.trimIndent()

    private val claimLua = """
        local qty = redis.call('HGET', KEYS[1], ARGV[1])
        if not qty then return 0 end
        redis.call('INCRBY', KEYS[2], qty)
        redis.call('HDEL', KEYS[1], ARGV[1])
        return 1
    """.trimIndent()

    private val releaseLua = """
        local qty = redis.call('HGET', KEYS[1], ARGV[1])
        if not qty then return 0 end
        redis.call('INCRBY', KEYS[2], qty)
        redis.call('HDEL', KEYS[1], ARGV[1])
        return 1
    """.trimIndent()

    override fun reset(items: List<ItemSpec>) {
        pool.resource.use { j ->
            j.flushDB()
            for (it in items) {
                j.set(stockKey(it.itemId, it.locationId), it.totalStock.toString())
                j.set(soldKey(it.itemId, it.locationId), "0")
                j.del(resKey(it.itemId, it.locationId))
            }
        }
    }

    override fun reserve(itemId: Long, locationId: Long, qty: Int): ReservationResult {
        val reservationId = ReservationId.create(itemId, locationId)
        val ok = pool.resource.use { j ->
            j.eval(
                reserveLua,
                listOf(stockKey(itemId, locationId), resKey(itemId, locationId)),
                listOf(qty.toString(), reservationId),
            ) as Long
        }
        return if (ok == 1L) ReservationResult(ReservationOutcome.SUCCESS, reservationId)
        else ReservationResult(ReservationOutcome.SOLD_OUT, null)
    }

    override fun claim(reservationId: String) {
        val p = ReservationId.parse(reservationId)
        pool.resource.use { j ->
            j.eval(claimLua, listOf(resKey(p.itemId, p.locationId), soldKey(p.itemId, p.locationId)), listOf(reservationId))
        }
    }

    override fun release(reservationId: String) {
        val p = ReservationId.parse(reservationId)
        pool.resource.use { j ->
            j.eval(releaseLua, listOf(resKey(p.itemId, p.locationId), stockKey(p.itemId, p.locationId)), listOf(reservationId))
        }
    }

    override fun snapshot(itemId: Long, locationId: Long): InventorySnapshot {
        pool.resource.use { j ->
            val available = j.get(stockKey(itemId, locationId))?.toLong() ?: 0
            val sold = j.get(soldKey(itemId, locationId))?.toLong() ?: 0
            val reserved = j.hvals(resKey(itemId, locationId)).sumOf { it.toLong() }
            return InventorySnapshot(sold = sold, reserved = reserved, available = available)
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew test --tests poc.strategy.RedisCounterStrategyIT`
Expected: PASS — 정확히 10건 성공, oversell 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/poc/config/RedisConfig.kt src/main/kotlin/poc/strategy/RedisCounterStrategy.kt src/test/kotlin/poc/strategy/RedisCounterStrategyIT.kt
git commit -m "feat: 전략 ② 순수 Redis 원자 카운터"
```

---

### Task 7: 전략 ③ RedisLedgerSplitStrategy (atomicity gap 재현)

**Files:**
- Create: `src/main/kotlin/poc/strategy/RedisLedgerSplitStrategy.kt`
- Test: `src/test/kotlin/poc/strategy/RedisLedgerSplitStrategyIT.kt`

> 의도된 결함 전략. check-then-act가 Redis 읽기 → MySQL 읽기 → Redis 쓰기로 비원자적이라 동시성에서 oversell이 발생해야 한다. 테스트는 "oversell이 일어남"을 확인한다.

- [ ] **Step 1: Write failing integration test**

```kotlin
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests poc.strategy.RedisLedgerSplitStrategyIT`
Expected: FAIL — 미정의

- [ ] **Step 3: RedisLedgerSplitStrategy.kt 작성**

```kotlin
package poc.strategy

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import poc.domain.InventorySnapshot
import poc.domain.ItemSpec
import poc.domain.ReservationId
import poc.domain.ReservationOutcome
import poc.domain.ReservationResult
import poc.domain.ReservationStrategy
import poc.domain.SHOP_ID
import redis.clients.jedis.JedisPool

/**
 * Shopify 구구조 재현: 예약 카운트는 Redis, 판매(원장)는 MySQL.
 * check(Redis reserved + MySQL sold 읽기) 와 act(Redis 증가)가 원자적이지 않아 oversell 발생.
 */
@Component
class RedisLedgerSplitStrategy(
    private val pool: JedisPool,
    private val jdbc: JdbcTemplate,
) : ReservationStrategy {

    override fun name() = "redis-ledger-split"

    private fun reservedKey(i: Long, l: Long) = "split:reserved:$i:$l"
    private fun resHash(i: Long, l: Long) = "split:res:$i:$l"

    override fun reset(items: List<ItemSpec>) {
        jdbc.update("DELETE FROM inventory_ledger")
        pool.resource.use { j ->
            for (it in items) {
                jdbc.update(
                    "INSERT INTO inventory_ledger(shop_id,item_id,location_id,total_quantity,sold_quantity) VALUES (?,?,?,?,0)",
                    SHOP_ID, it.itemId, it.locationId, it.totalStock.toLong(),
                )
                j.set(reservedKey(it.itemId, it.locationId), "0")
                j.del(resHash(it.itemId, it.locationId))
            }
        }
    }

    override fun reserve(itemId: Long, locationId: Long, qty: Int): ReservationResult {
        // --- CHECK: 두 시스템에서 따로 읽음 (비원자) ---
        val total = jdbc.queryForObject(
            "SELECT total_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        val sold = jdbc.queryForObject(
            "SELECT sold_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        val reserved = pool.resource.use { it.get(reservedKey(itemId, locationId))?.toLong() ?: 0 }
        if (total - sold - reserved < qty) {
            return ReservationResult(ReservationOutcome.SOLD_OUT, null)
        }
        // --- ACT: 락 없이 Redis 증가 (CHECK 와 ACT 사이에 gap) ---
        val reservationId = ReservationId.create(itemId, locationId)
        pool.resource.use { j ->
            j.incrBy(reservedKey(itemId, locationId), qty.toLong())
            j.hset(resHash(itemId, locationId), reservationId, qty.toString())
        }
        return ReservationResult(ReservationOutcome.SUCCESS, reservationId)
    }

    override fun claim(reservationId: String) {
        val p = ReservationId.parse(reservationId)
        val qty = pool.resource.use { it.hget(resHash(p.itemId, p.locationId), reservationId)?.toLong() } ?: return
        // 두 시스템 분리 차감: MySQL sold += qty, Redis reserved -= qty
        jdbc.update(
            "UPDATE inventory_ledger SET sold_quantity=sold_quantity+? WHERE shop_id=? AND item_id=? AND location_id=?",
            qty, SHOP_ID, p.itemId, p.locationId,
        )
        pool.resource.use { j ->
            j.decrBy(reservedKey(p.itemId, p.locationId), qty)
            j.hdel(resHash(p.itemId, p.locationId), reservationId)
        }
    }

    override fun release(reservationId: String) {
        val p = ReservationId.parse(reservationId)
        val qty = pool.resource.use { it.hget(resHash(p.itemId, p.locationId), reservationId)?.toLong() } ?: return
        pool.resource.use { j ->
            j.decrBy(reservedKey(p.itemId, p.locationId), qty)
            j.hdel(resHash(p.itemId, p.locationId), reservationId)
        }
    }

    override fun snapshot(itemId: Long, locationId: Long): InventorySnapshot {
        val total = jdbc.queryForObject(
            "SELECT total_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        val sold = jdbc.queryForObject(
            "SELECT sold_quantity FROM inventory_ledger WHERE shop_id=? AND item_id=? AND location_id=?",
            Long::class.java, SHOP_ID, itemId, locationId,
        ) ?: 0
        val reserved = pool.resource.use { it.get(reservedKey(itemId, locationId))?.toLong() ?: 0 }
        val available = (total - sold - reserved).coerceAtLeast(0)
        return InventorySnapshot(sold = sold, reserved = reserved, available = available)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests poc.strategy.RedisLedgerSplitStrategyIT`
Expected: PASS — successes > 10 (oversell 재현). 드물게 경합이 약하면 클라이언트 수를 늘려 재현.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/poc/strategy/RedisLedgerSplitStrategy.kt src/test/kotlin/poc/strategy/RedisLedgerSplitStrategyIT.kt
git commit -m "feat: 전략 ③ Redis+MySQL 분리 (atomicity gap 재현)"
```

---

### Task 8: 시나리오 + BenchmarkRunner

**Files:**
- Create: `src/main/kotlin/poc/bench/Scenario.kt`
- Create: `src/main/kotlin/poc/bench/BenchmarkRunner.kt`
- Test: `src/test/kotlin/poc/bench/ScenarioTest.kt`

- [ ] **Step 1: Write failing test for scenario item layout**

```kotlin
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests poc.bench.ScenarioTest`
Expected: FAIL — `Scenario` 미정의

- [ ] **Step 3: Scenario.kt 작성**

```kotlin
package poc.bench

import poc.domain.ItemSpec

/**
 * 각 시나리오는 아이템 레이아웃과 클라이언트의 1회 동작(action)을 정의한다.
 * action 은 reserve 결과에 따라 claim/release 를 호출해 시나리오 성격을 만든다.
 */
enum class Scenario(private val baseItem: Long) {
    /** 1개 핫 아이템, 충분한 재고. reserve→즉시 release 로 재고를 순환시켜 순수 락 경합/처리량 측정. */
    HOT_SINGLE(1000) {
        override fun items() = listOf(ItemSpec(baseItem, 1, 1000))
        override fun pickItem(clientIndex: Int) = baseItem
        override fun afterReserve() = AfterReserve.RELEASE
    },
    /** 재고 10개에 다수 클라이언트. reserve→claim(반환 없음). oversell 검증의 핵심. */
    LAST_UNITS(2000) {
        override fun items() = listOf(ItemSpec(baseItem, 1, 10))
        override fun pickItem(clientIndex: Int) = baseItem
        override fun afterReserve() = AfterReserve.CLAIM
    },
    /** 1000개 아이템에 분산, 각 50개 재고. 현실적 저경합 처리량. reserve→claim. */
    MIXED(3000) {
        override fun items() = (0 until 1000).map { ItemSpec(baseItem + it, 1, 50) }
        override fun pickItem(clientIndex: Int) = baseItem + (clientIndex % 1000)
        override fun afterReserve() = AfterReserve.CLAIM
    };

    abstract fun items(): List<ItemSpec>
    abstract fun pickItem(clientIndex: Int): Long
    abstract fun afterReserve(): AfterReserve
    val locationId: Long get() = 1
}

enum class AfterReserve { CLAIM, RELEASE }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests poc.bench.ScenarioTest`
Expected: PASS (3 tests)

- [ ] **Step 5: BenchmarkRunner.kt 작성**

```kotlin
package poc.bench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import poc.domain.ReservationOutcome
import poc.domain.ReservationStrategy

data class BenchConfig(val clients: Int = 200, val warmupRounds: Int = 2, val measuredRounds: Int = 5)

data class BenchResult(
    val strategy: String,
    val scenario: String,
    val throughputPerSec: Double,
    val latency: LatencyStats,
    val success: Long,
    val soldOut: Long,
    val errors: Long,
    val oversell: Long,
    val conserved: Boolean,
)

class BenchmarkRunner(private val config: BenchConfig = BenchConfig()) {

    fun run(strategy: ReservationStrategy, scenario: Scenario): BenchResult {
        strategy.reset(scenario.items())

        // 워밍업 (측정 제외)
        repeat(config.warmupRounds) { oneRound(strategy, scenario) }
        strategy.reset(scenario.items())

        // 측정
        val startNs = System.nanoTime()
        val rounds = (1..config.measuredRounds).map { oneRound(strategy, scenario) }
        val wallSec = (System.nanoTime() - startNs) / 1_000_000_000.0
        val merged = rounds.flatten().merge()

        // 정합성: 시나리오의 모든 아이템 합산
        var totalOversell = 0L
        var allConserved = true
        for (item in scenario.items()) {
            val snap = strategy.snapshot(item.itemId, scenario.locationId)
            val report = CorrectnessOracle.check(snap, item.totalStock.toLong())
            totalOversell += report.oversell
            if (!report.conserved) allConserved = false
        }

        return BenchResult(
            strategy = strategy.name(),
            scenario = scenario.name,
            throughputPerSec = merged.success / wallSec,
            latency = LatencyStats.from(merged.latenciesNs),
            success = merged.success,
            soldOut = merged.soldOut,
            errors = merged.errors,
            oversell = totalOversell,
            conserved = allConserved,
        )
    }

    private fun oneRound(strategy: ReservationStrategy, scenario: Scenario): List<ClientResult> = runBlocking {
        (0 until config.clients).map { idx ->
            async(Dispatchers.IO) {
                val res = ClientResult()
                val itemId = scenario.pickItem(idx)
                val t0 = System.nanoTime()
                try {
                    val r = strategy.reserve(itemId, scenario.locationId, 1)
                    res.latenciesNs.add(System.nanoTime() - t0)
                    when (r.outcome) {
                        ReservationOutcome.SUCCESS -> {
                            res.success++
                            when (scenario.afterReserve()) {
                                AfterReserve.CLAIM -> strategy.claim(r.reservationId!!)
                                AfterReserve.RELEASE -> strategy.release(r.reservationId!!)
                            }
                        }
                        ReservationOutcome.SOLD_OUT -> res.soldOut++
                    }
                } catch (e: Exception) {
                    res.errors++
                }
                res
            }
        }.awaitAll()
    }
}
```

> 참고: `AfterReserve`(CLAIM/RELEASE)와 `Scenario`는 같은 `poc.bench` 패키지(Scenario.kt)에 있으므로 BenchmarkRunner에서 별도 import 불필요.

- [ ] **Step 6: Run full test suite**

Run: `./gradlew test --tests poc.bench.ScenarioTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/poc/bench/Scenario.kt src/main/kotlin/poc/bench/BenchmarkRunner.kt src/test/kotlin/poc/bench/ScenarioTest.kt
git commit -m "feat: 시나리오 3종 + BenchmarkRunner (워밍업 분리)"
```

---

### Task 9: CLI 실행 + 결과 리포트

**Files:**
- Create: `src/main/kotlin/poc/BenchmarkCommandLineRunner.kt`
- Create: `src/main/kotlin/poc/bench/ResultPrinter.kt`
- Test: `src/test/kotlin/poc/bench/ResultPrinterTest.kt`

- [ ] **Step 1: Write failing test for result table formatting**

```kotlin
package poc.bench

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultPrinterTest {
    @Test
    fun `table contains strategy name and oversell column`() {
        val results = listOf(
            BenchResult("mysql-skip-locked", "LAST_UNITS", 1234.5, LatencyStats(10, 20, 30, 100), 10, 190, 0, 0, true),
        )
        val table = ResultPrinter.toMarkdown(results)
        assertTrue(table.contains("mysql-skip-locked"))
        assertTrue(table.contains("oversell"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests poc.bench.ResultPrinterTest`
Expected: FAIL — `ResultPrinter` 미정의

- [ ] **Step 3: ResultPrinter.kt 작성**

```kotlin
package poc.bench

object ResultPrinter {
    fun toMarkdown(results: List<BenchResult>): String {
        val sb = StringBuilder()
        sb.appendLine("| strategy | scenario | throughput/s | p50(µs) | p95(µs) | p99(µs) | success | soldOut | errors | oversell | conserved |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in results) {
            sb.appendLine(
                "| ${r.strategy} | ${r.scenario} | ${"%.1f".format(r.throughputPerSec)} | " +
                    "${r.latency.p50 / 1000} | ${r.latency.p95 / 1000} | ${r.latency.p99 / 1000} | " +
                    "${r.success} | ${r.soldOut} | ${r.errors} | ${r.oversell} | ${r.conserved} |",
            )
        }
        return sb.toString()
    }
}
```

- [ ] **Step 4: BenchmarkCommandLineRunner.kt 작성**

```kotlin
package poc

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import poc.bench.BenchConfig
import poc.bench.BenchmarkRunner
import poc.bench.BenchResult
import poc.bench.ResultPrinter
import poc.bench.Scenario
import poc.domain.ReservationStrategy

@Component
class BenchmarkCommandLineRunner(
    private val strategies: List<ReservationStrategy>,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        // --strategy=mysql-skip-locked (없으면 전체), --scenario=LAST_UNITS (없으면 전체)
        val strategyFilter = args.getOptionValues("strategy")?.firstOrNull()
        val scenarioFilter = args.getOptionValues("scenario")?.firstOrNull()
        val clients = args.getOptionValues("clients")?.firstOrNull()?.toInt() ?: 200

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

        // 콘솔 전용 실행 후 종료
        context.close()
    }
}
```

- [ ] **Step 5: Run to verify ResultPrinter test passes**

Run: `./gradlew test --tests poc.bench.ResultPrinterTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/poc/BenchmarkCommandLineRunner.kt src/main/kotlin/poc/bench/ResultPrinter.kt src/test/kotlin/poc/bench/ResultPrinterTest.kt
git commit -m "feat: CLI 실행기 + 마크다운 결과 리포트"
```

---

### Task 10: 전체 실행 + 결과 분석 문서

**Files:**
- Create: `docs/results/2026-06-02-phase1-results.md`

- [ ] **Step 1: 인프라 기동**

Run: `docker compose up -d`
Expected: mysql healthy, redis up (`docker compose ps`)

- [ ] **Step 2: 전체 벤치마크 실행 (기본 풀 크기)**

Run: `POOL_SIZE=50 ./gradlew bootRun --args='--clients=200'`
Expected: 9행(전략 3 × 시나리오 3) 마크다운 표 출력. 검증 포인트:
- `mysql-skip-locked` / `redis-counter` → 전 시나리오 `oversell=0`, `conserved=true`
- `redis-ledger-split` / `LAST_UNITS` → `oversell>0`, `conserved=false`

- [ ] **Step 3: 커넥션 풀 고갈 관찰 (글의 핵심 통찰 재현)**

Run: `POOL_SIZE=5 ./gradlew bootRun --args='--clients=200 --strategy=mysql-skip-locked --scenario=HOT_SINGLE'`
그리고 비교: `POOL_SIZE=50 ./gradlew bootRun --args='--clients=200 --strategy=mysql-skip-locked --scenario=HOT_SINGLE'`
Expected: 풀 5일 때 throughput이 급감(쿼리 자체는 빠른데 커넥션 대기로 막힘). 두 수치를 결과 문서에 기록.

- [ ] **Step 4: 결과 분석 문서 작성**

`docs/results/2026-06-02-phase1-results.md` 에 다음을 기록:
- 실행 환경(머신/도커 버전), 설정(clients, pool size, rounds)
- Step 2의 9행 결과표 붙여넣기
- Step 3의 풀 크기별 throughput 비교표
- 3-way 장단점 분석: ① ACID·safe neighbor / ② 최고 처리량·원장 정합성은 별도 문제 / ③ atomicity gap으로 oversell
- 커넥션 풀 관찰에서 얻은 교훈(쿼리 ≠ 병목, 커넥션 점유가 병목)
- 한계: 로컬 단일 머신이라 상대 비교만 유효, replenishment 미포함(Phase 2)

- [ ] **Step 5: Commit**

```bash
git add docs/results/2026-06-02-phase1-results.md
git commit -m "docs: Phase 1 벤치마크 결과 + 3-way 분석"
```

---

## Self-Review

**Spec coverage 체크 (스펙 → 태스크 매핑):**
- §2 전략 3종 → Task 5/6/7 ✓
- §3 아키텍처(단일 앱+Strategy, in-process) → Task 3 인터페이스 + Task 8 러너 ✓
- §4 인터페이스 → Task 3 ✓
- §5 데이터 모델(복합 PK, SKIP LOCKED, lock ordering, READ COMMITTED, totalStock≤1000) → Task 2 스키마 + Task 5 + Scenario 재고값(10/50/1000) ✓
- §6 부하 하니스/오라클/시나리오/워밍업/메트릭 → Task 4 + Task 8 ✓
- §7 커넥션 풀 노브 → application.yml `POOL_SIZE` + Task 10 Step 3 관찰 ✓
- §8 인프라/Flyway/산출물 → Task 1/2/10 ✓
- §9 Phase 1 범위 → 전 태스크가 Phase 1; replenishment 제외 ✓
- §10 스코프 제외(TTL/HTTP/멀티로케이션/멀티테넌시) → 미구현 확인 ✓
- §11 GitHub → 본 계획 범위 외(별도 단계에서 레포 생성·푸시) — 실행 핸드오프 후 처리

**Placeholder scan:** 없음. 모든 코드 스텝에 완전한 코드 포함.

**Type consistency 체크:**
- `ReservationStrategy` 시그니처(reset/reserve/claim/release/snapshot/name)가 Task 3 정의와 Task 5/6/7 구현, Task 8 호출에서 일치 ✓
- `ReservationResult(outcome, reservationId)`, `InventorySnapshot(sold, reserved, available)`, `ItemSpec(itemId, locationId, totalStock)` 전 태스크 일치 ✓
- `ClientResult`/`LatencyStats`/`merge()`/`OracleReport`/`BenchResult` 명칭 Task 4·8·9 일치 ✓
- `AfterReserve`(CLAIM/RELEASE)는 `poc.bench` 패키지에 위치 — Task 8 BenchmarkRunner의 jurpast import 잔재 경고를 명시해 불일치 방지 ✓
