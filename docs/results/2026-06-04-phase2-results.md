# Phase 2 실험 결과 — 격리수준 / Bounded Pool 분석

작성일: 2026-06-04

---

## 1. 실행 환경

| 항목 | 값 |
|---|---|
| OS | macOS Darwin 24.6.0 (Apple Silicon, 단일 머신) |
| DB | Docker `mysql:8.0`, `--innodb-lock-wait-timeout=3` |
| Runtime | Java 17.0.18, Gradle 8.12, Spring Boot 3.4.1 |
| E1 기본값 | rounds=10, clients=100 (추가 시도: clients=200) |
| E2 기본값 | rounds=20, clients=100, ledgerTotal=5000, poolCap=1000 |

---

## 2. E1 결과 — 격리수준별 락 충돌

**시도 1 (clients=100, 1차)**

| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |
|---|---|---|---|---|---|
| REPEATABLE_READ | 0 | 0 | 0 | 1000 | 0 |
| READ_COMMITTED | 0 | 0 | 0 | 1000 | 0 |

**시도 2 (clients=100, 2차 재실행)**

| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |
|---|---|---|---|---|---|
| REPEATABLE_READ | 0 | 0 | 0 | 1000 | 0 |
| READ_COMMITTED | 0 | 0 | 0 | 1000 | 0 |

**시도 3 (clients=200, 재시도)**

| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |
|---|---|---|---|---|---|
| REPEATABLE_READ | 0 | 0 | 0 | 2000 | 0 |
| READ_COMMITTED | 0 | 0 | 0 | 2000 | 0 |

총 3회 시도 모두에서 REPEATABLE_READ의 deadlocks/lockWaitTimeouts는 0이었다.

---

## 3. E1 해석

### 이론적 기대

REPEATABLE_READ에서 `SELECT ... FOR UPDATE SKIP LOCKED`는 조회한 인덱스 범위의 끝(supremum pseudo-record)에 **gap lock**을 설정한다. `replenishment`가 해당 범위 안에 새 행을 `INSERT`하려 할 때 이 gap lock과 충돌해 데드락(1213) 또는 락 대기 타임아웃(1205)이 발생할 수 있다. READ COMMITTED는 gap lock을 설정하지 않으므로 충돌이 없다. Shopify가 REPEATABLE READ → READ COMMITTED로 전환한 이유와 같은 원리다.

### 실제 결과와 차이가 생긴 원인

3회 시도에서 REPEATABLE_READ도 충돌이 발생하지 않았다. 원인은 다음 복합 요인으로 추정된다.

1. **SKIP LOCKED의 gap lock 완화**: `SKIP LOCKED`는 이미 잠긴 행을 스킵하기 때문에, 정상 `SELECT ... FOR UPDATE`보다 supremum gap lock 범위가 좁아지거나 충돌이 발생하는 타이밍이 줄어든다.
2. **단일 머신 JVM 스케줄러 직렬화**: 코루틴이 `Dispatchers.IO` 스레드 풀을 공유하는 구조에서, 실제 멀티 노드 분산 환경과 달리 트랜잭션 구간이 완전히 겹치지 않을 수 있다. reserve와 replenishment가 교대로 실행되는 패턴이 되면 gap 충돌 윈도우가 줄어든다.
3. **poolCap=1000, 빠른 소진**: 풀이 빠르게 비워지면 replenishment가 INSERT를 시도할 때 reserve 트랜잭션이 이미 커밋된 후여서 gap lock이 해제된 상태가 된다.

이 실험에서는 **데드락을 재현하지 못했음을 솔직히 밝힌다.** 이론적 인과관계(gap lock 충돌)는 유효하지만, 로컬 단일 머신의 동시성 특성상 타이밍이 맞지 않아 확률적으로 발생하지 않은 것으로 판단한다.

---

## 4. E2 결과 — Bounded Pool 정합성

**실행 (clients=100, rounds=20, ledgerTotal=5000, poolCap=1000)**

| total | successes(sold) | oversell | conserved | maxPoolObserved | finalPool |
|---|---|---|---|---|---|
| 5000 | 2000 | 0 | true | 1000 | 1000 |

---

## 5. E2 해석

- **oversell = 0**: 100 클라이언트가 20 라운드 동안 동시에 reserve→claim을 수행했지만 `sold + reserved`가 `total(5000)`을 초과한 적이 없다. `SELECT ... FOR UPDATE SKIP LOCKED`의 배타적 행 잠금이 중복 예약을 완전히 차단했다.
- **maxPoolObserved = 1000**: `poolCap` 상한을 한 번도 초과하지 않았다. replenishment는 풀 행 수가 poolCap 미만일 때만 INSERT하는 조건을 준수했다.
- **conserved = true**: 최종 상태에서 `sold + reserved ≤ total`이 성립한다.
- **finalPool = 1000**: 20라운드 내에 ledgerTotal=5000 중 2000(sold)만 소진되었고, 나머지 재고 범위 안에서 풀이 꽉 채워진 상태로 종료됐다.

bounded pool 설계가 **고부하 동시 접근 하에서도 oversell 없이 재고 정합성을 유지**함을 확인했다.

---

## 6. 한계

| 항목 | 내용 |
|---|---|
| 단일 머신 실행 | 분산 멀티 노드 환경이 아니어서 실제 운영 동시성 수준을 재현하지 못함 |
| E1 데드락 미재현 | 스케줄러 타이밍 의존성으로 3회 시도에서 gap lock 충돌이 발생하지 않음; 이론적 위험은 유효하나 실험으로 확인되지 않음 |
| lock-wait timeout 인위 단축 | 3초로 설정해 타임아웃 발생 기회를 높였으나 E1에서는 효과 없음 |
| E3 미구현 | Phase 2 스펙의 선택 항목(lock ordering 실험)은 이번 PoC에서 구현하지 않음 |
| 클라이언트 수 제한 | 최대 clients=200으로 시도; 더 높은 동시성에서는 결과가 달라질 수 있음 |
