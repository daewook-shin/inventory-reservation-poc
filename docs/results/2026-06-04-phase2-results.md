# Phase 2 실험 결과 — 격리수준 / Bounded Pool 분석

작성일: 2026-06-04

---

## 1. 실행 환경

| 항목 | 값 |
|---|---|
| OS | macOS Darwin 24.6.0 (Apple Silicon, 단일 머신) |
| DB | Docker `mysql:8.0`, `--innodb-lock-wait-timeout=3` |
| Runtime | Java 17.0.18, Gradle 8.12, Spring Boot 3.4.1 |
| E1 realistic | rounds=10, clients=100 |
| E1b minimal demo | rounds=20 (배리어 동기화) |
| E2 | rounds=20, clients=100, ledgerTotal=5000, poolCap=1000 |

---

## 2. E1 결과 — 격리수준별 락 충돌 (realistic, reserve+replenishment)

**실행: `--experiment=E1 --clients=100`**

| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |
|---|---|---|---|---|---|
| REPEATABLE_READ | 0 | 0 | 0 | 1000 | 0 |
| READ_COMMITTED | 0 | 0 | 0 | 1000 | 0 |

---

## 3. E1 해석 — 왜 자연 경로로는 재현 안 되는가

`SELECT … FOR UPDATE SKIP LOCKED LIMIT 1`은 잠기지 않은 행이 풀에 있으면 낮은 id에서 이른 종료하므로 supremum pseudo-record까지 스캔하지 않는다. 따라서 supremum gap lock을 획득하지 않는다.

행을 전혀 찾지 못하는 경우(soldOut)에는 빈 결과를 받자마자 즉시 롤백하기 때문에 gap lock 보유 시간이 사실상 0이다.

또한 INSERT 주체가 replenishment 단일 goroutine뿐이므로 순환 대기의 짝을 만들기 어렵다.

결론적으로 **SKIP LOCKED + bounded pool 조합이 (역설적으로) supremum 스캔을 회피한다** — 이 자체가 중요한 발견이다. gap lock 위험이 없다는 뜻이 아니라, 자연스러운 reserve+replenishment 경로에서는 타이밍이 맞아야 하는 경합 조건이 극히 드물다는 것을 보여 준다.

---

## 4. E1b 결과 — gap-lock 데드락 최소 재현 (배리어 동기화)

**실행: `--experiment=E1 --clients=100` (E1b는 E1 실행에 포함)**

| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes |
|---|---|---|---|---|
| REPEATABLE_READ | 20 | 0 | 0 | 20 |
| READ_COMMITTED | 0 | 0 | 0 | 40 |

---

## 5. E1b 해석 — gap-lock 데드락 메커니즘

E1b(`runGapLockDemo`)는 빈 테이블(`gap_lock_demo`)에서 두 트랜잭션이 `WHERE id > 0 FOR UPDATE`로 전 범위를 스캔하도록 강제하고, **CyclicBarrier**로 두 트랜잭션이 supremum 스캔 직후에 동기화된 뒤 동시에 INSERT를 시도하게 설계한다.

**메커니즘**:

1. 테이블이 비어 있으므로 `WHERE id > 0 FOR UPDATE` 스캔이 반드시 supremum pseudo-record까지 도달한다.
2. 두 트랜잭션이 supremum에 **공유 gap lock(shared gap lock)** 을 동시에 획득한다. gap lock끼리는 호환되므로 이 단계에서 블로킹은 없다.
3. 배리어에서 동기화 후 양쪽이 동일 gap 안으로 INSERT를 시도한다.
4. INSERT는 **insert-intention gap lock**을 요구하는데, 이 lock은 상대방이 보유한 공유 gap lock과 충돌한다.
5. 양쪽이 서로 상대의 gap lock이 해제되길 기다리는 **순환 대기** → InnoDB가 한쪽을 데드락(1213)으로 종료.
6. READ COMMITTED는 gap lock을 애초에 획득하지 않으므로 두 INSERT가 모두 성공한다(총 40건).

결과표에서 RR 라운드 20회 모두 데드락(20건)이 발생하고 RC는 0건인 것이 이 메커니즘을 **결정적으로** 입증한다. 이것이 Shopify가 REPEATABLE READ → READ COMMITTED로 전환한 핵심 이유다.

> **구현 노트**: 데모 테이블을 매 라운드 비워야(truncate) next-key 레코드 락이 특정 레코드를 직렬화하는 상황을 피하고 순수 gap lock 경쟁만 남길 수 있어 결정적 재현이 가능하다.

---

## 6. E2 결과 — Bounded Pool 정합성

**실행: `--experiment=E2 --clients=100`**

| total | successes(sold) | oversell | conserved | maxPoolObserved | finalPool |
|---|---|---|---|---|---|
| 5000 | 2000 | 0 | true | 1000 | 1000 |

---

## 7. E2 해석

- **oversell = 0**: 100 클라이언트가 20 라운드 동안 동시에 reserve→claim을 수행했지만 `sold + reserved`가 `total(5000)`을 초과한 적이 없다. `SELECT … FOR UPDATE SKIP LOCKED`의 배타적 행 잠금이 중복 예약을 완전히 차단했다.
- **maxPoolObserved = 1000**: `poolCap` 상한을 한 번도 초과하지 않았다. replenishment는 풀 행 수가 poolCap 미만일 때만 INSERT하는 조건을 준수했다.
- **conserved = true**: 최종 상태에서 `sold + reserved ≤ total`이 성립한다.
- **finalPool = 1000**: 20라운드 내에 ledgerTotal=5000 중 2000(sold)만 소진되었고, 나머지 재고 범위 안에서 풀이 꽉 채워진 상태로 종료됐다.

bounded pool 설계가 **고부하 동시 접근 하에서도 oversell 없이 재고 정합성을 유지**함을 확인했다.

---

## 8. 한계

| 항목 | 내용 |
|---|---|
| 단일 머신 실행 | 분산 멀티 노드 환경이 아니어서 실제 운영 동시성 수준을 재현하지 못함 |
| E1 realistic 미재현 | SKIP LOCKED + bounded pool 조합이 역설적으로 supremum 스캔을 회피하므로, 자연 경로(reserve+replenishment)에서는 gap lock 충돌 타이밍이 극히 드물다; 메커니즘은 E1b로 입증 |
| lock-wait timeout 인위 단축 | 3초로 설정해 타임아웃 발생 기회를 높였으나 E1 realistic에서는 효과 없음 |
| E3 미구현 | Phase 2 스펙의 선택 항목(lock ordering 실험)은 이번 PoC에서 구현하지 않음 |
