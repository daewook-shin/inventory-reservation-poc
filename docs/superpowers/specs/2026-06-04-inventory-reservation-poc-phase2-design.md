# 재고 예약 PoC — Phase 2 설계 (DB 병리 동작 재현)

> 작성일: 2026-06-04
> 선행: [Phase 1 설계](2026-06-02-inventory-reservation-poc-design.md) / [Shopify 글](https://shopify.engineering/scaling-inventory-reservations)

## 1. 목적

Phase 1이 검증하지 않은, Shopify 글의 깊은 DB 디테일을 로컬에서 직접 재현·측정한다.

- **E1 (핵심)**: replenishment 잡과 reserve가 동시에 돌 때, 트랜잭션 격리수준이 데드락을 만드는지. REPEATABLE READ의 gap-lock 데드락 vs READ COMMITTED의 깨끗함.
- **E2**: bounded pool(1000행 상한) + replenishment 보충 사이클이 재고 소진 중에도 oversell 0을 유지하는지.
- **E3 (선택/스트레치)**: lock ordering이 어긋나면 순환 대기 데드락이 나고, 순서를 고정하면 사라지는지.

## 2. 구조 — `poc.experiment` 패키지

Phase 1 전략/벤치 코드는 **불변**. Phase 1 테이블(`reservation_units`, `reserved_quantities`, `inventory_ledger`)을 재사용한다.

```
ExperimentCommandLineRunner 분기 (--experiment=E1|E2|all, E3는 선택 구현 시 추가)
        │
        ├─ ReservationDao        격리수준·lock-order를 파라미터로 받는 전용 reserve/claim/release DAO
        ├─ ReplenishmentJob      원장 기준으로 bounded pool(상한 1000행)을 보충(INSERT)
        └─ ExperimentRunner      동시 부하 구동 + 데드락/타임아웃/롤백/성공 카운트 → 비교 리포트
```

- **ReservationDao**: 생성자가 아니라 메서드 단위로 격리수준(`Isolation`)과 lock-order(`LockOrder`)를 받는다. `ExperimentRunner`가 원하는 조합으로 `TransactionTemplate`을 만들어 주입한다. Phase 1의 `MySqlSkipLockedStrategy`와 SQL은 동일하되, 격리수준/순서를 외부에서 바꿀 수 있는 점만 다르다.
- **ReplenishmentJob**: `available = COUNT(reservation_units)`가 임계치(예: 상한의 20%) 이하로 떨어지면, 원장의 `total - sold - reserved - available`만큼(단, 풀 상한 1000을 넘지 않게) `reservation_units`에 새 unit 행을 INSERT. 백그라운드 코루틴으로 반복 실행.
- **ExperimentRunner**: 실험별로 시드 → 동시 reserve 부하 + 필요 시 ReplenishmentJob 가동 → 종료 후 카운트 집계 + 오라클 검증.

## 3. 데드락 감지

reserve/claim 실행 중 발생하는 `SQLException`을 캐치하여 분류·집계한다.

| 분류 | 식별 |
|---|---|
| deadlock | `SQLState == "40001"` (InnoDB deadlock, MySQL error 1213) |
| lock-wait timeout | MySQL error code `1205` (SQLState `HY000`) |
| 기타 롤백 | 그 외 SQLException |

집계 결과는 `DeadlockStats(deadlocks, lockWaitTimeouts, otherErrors, successes)`로 표현.

## 4. 실험 정의

### E1 — 격리수준 데드락 (핵심)
- 시드: 단일 item, 재고(원장 total) = 3000, 풀 상한 = 1000 (→ replenishment가 반복적으로 INSERT).
- 부하: 다수 동시 클라이언트가 reserve(SELECT … FOR UPDATE SKIP LOCKED + DELETE + INSERT) 반복 + ReplenishmentJob 동시 가동.
- 두 격리수준으로 각각 실행: `REPEATABLE_READ`, `READ_COMMITTED`.
- 측정: 격리수준별 `DeadlockStats`를 나란히. 기대(문서 기록 대상): RR에서 deadlock/timeout > 0, RC에서 0.

### E2 — Bounded pool + replenishment
- 시드: 단일 item, 원장 total = 5000, 풀 상한 = 1000.
- 부하: 다수 클라이언트가 reserve→claim 반복(총 수요가 5000을 넘도록) + ReplenishmentJob 가동. 격리수준은 READ COMMITTED 고정.
- 검증(결정적, 단언): 총 성공(claim 완료) ≤ 5000 (**oversell 0**); 어느 시점에도 풀 행 수 ≤ 1000; 종료 후 보존 법칙(`sold + reserved + available_in_ledger == total`) 성립.

### E3 — lock ordering 데드락 대조 (선택/스트레치)
- 두 작업을 동시 실행: (a) `LockOrder.UNITS_FIRST`(reservation_units→reserved_quantities), (b) 의도적 `LockOrder.RESERVED_FIRST`(역순). 동일 행 집합을 두고 경쟁 → 순환 대기 데드락.
- 그 다음 양쪽을 `UNITS_FIRST`로 통일 → 데드락 소멸.
- 측정: 혼합 순서 vs 통일 순서의 deadlock 카운트 대조.

## 5. 테스트 전략

Phase 1과 동일 철학:
- **E2는 결정적** → 통합테스트로 oversell 0 + 풀 상한 ≤1000 + 보존 법칙 단언.
- **E1/E3 데드락은 확률적** → 통합테스트는 **깨끗한 경로만 단언**: READ COMMITTED(E1)·고정순서(E3) 실행이 `deadlocks == 0`으로 완료되고 보존 법칙이 성립함. "REPEATABLE READ/혼합순서에서 데드락 발생"은 flaky 단언을 피하고 **실제 실행 수치를 results 문서에 기록**한다.

## 6. CLI

기존 `BenchmarkCommandLineRunner.run()` 최상단에 분기 추가:
- `--experiment` 인자가 있으면 `ExperimentRunner`에 위임하고 벤치마크는 건너뛴다(그 후 동일하게 `context.close()`).
- 없으면 기존 Phase 1 벤치마크 경로.
- 값: `--experiment=E1|E2|all` (E3 구현 시 `E3` 추가). `--isolation`(E1 단독 실행용, 선택), `--clients` 재사용.

별도 ApplicationRunner를 추가하지 않는다(둘 다 부팅 시 실행되어 컨텍스트 종료가 충돌).

## 7. 산출물

`docs/results/2026-06-04-phase2-results.md`:
- E1: 격리수준별 deadlock/timeout/success 카운트 표 + 해석(gap-lock 메커니즘).
- E2: 풀 상한·oversell·보존 검증 결과.
- (E3 구현 시) 혼합 vs 고정 순서 데드락 대조표.
- 한계: 로컬 단일 머신, 스케줄러 의존 확률성.

## 8. YAGNI 제외

- 복합 PK 락 2→1 실험 (Phase 1에서 이미 복합 PK 사용 → 재현 가치 낮음)
- ProxySQL / per-caller 커넥션 태깅 (인프라 과중)
- E3는 **선택 항목** — E1·E2 완료 후 여력이 되면 구현. 인위적 역순 작업이 필요해 교육 가치가 E1/E2보다 낮음.
