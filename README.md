# inventory-reservation-poc

[Shopify — Scaling Inventory Reservations](https://shopify.engineering/scaling-inventory-reservations) 글에서 영감받은 **재고 예약 3-way 비교 PoC**. 동일한 부하 하니스로 세 가지 예약 전략을 돌려 정합성과 처리량을 비교한다.

| 전략 | 방식 | 핵심 |
|---|---|---|
| ① `mysql-skip-locked` | one-row-per-unit + `FOR UPDATE SKIP LOCKED` (READ COMMITTED) | ACID, oversell 0 |
| ② `redis-counter` | Lua 원자 카운터 | 최고 처리량, oversell 0 |
| ③ `redis-ledger-split` | Redis 예약 + MySQL 원장 (비원자) | atomicity gap → oversell 발생 |

## Prerequisites

- JDK 17, Docker (compose v2)
- **모든 통합 테스트와 벤치마크는 Docker로 띄운 MySQL 8.0 + Redis 6가 필요하다.** Docker 없이 `./gradlew test`를 돌리면 통합 테스트(`*IT`)가 커넥션 에러로 실패한다.

## Quickstart

```bash
# 1) 인프라 기동 (mysql:8.0 + redis:6-alpine)
docker compose up -d

# 2) 테스트 (단위 + 통합)
./gradlew test

# 3) 전체 벤치마크 (3 전략 × 3 시나리오)
POOL_SIZE=50 ./gradlew bootRun --args='--clients=200'

# 특정 전략/시나리오만:
./gradlew bootRun --args='--clients=200 --strategy=redis-counter --scenario=LAST_UNITS'

# 커넥션 풀 영향 관찰 (쿼리가 아니라 커넥션 점유가 병목):
POOL_SIZE=5  ./gradlew bootRun --args='--clients=200 --strategy=mysql-skip-locked --scenario=HOT_SINGLE'
POOL_SIZE=50 ./gradlew bootRun --args='--clients=200 --strategy=mysql-skip-locked --scenario=HOT_SINGLE'
```

옵션: `--strategy`(생략 시 전체), `--scenario`(생략 시 전체), `--clients`(기본 200), 환경변수 `POOL_SIZE`(HikariCP 최대 풀, 기본 50).

시나리오: `HOT_SINGLE`(1개 핫 아이템, 락 경합), `LAST_UNITS`(재고 10 — oversell 검증), `MIXED`(1000개 분산).

## 문서

- **Tech-share 발표 페이지**(자기완결 정적 HTML):
  - [docs/tech-share/skip-locked.html](docs/tech-share/skip-locked.html) — **`FOR UPDATE SKIP LOCKED` 집중**(배경·기존 잠금과의 차이·함정)
  - [docs/tech-share/index.html](docs/tech-share/index.html) — 3-way 트레이드오프 + 커넥션 병목 종합본
- 설계 스펙: [docs/superpowers/specs/](docs/superpowers/specs/)
- 구현 계획: [docs/superpowers/plans/](docs/superpowers/plans/)
- **벤치마크 결과·분석**: [Phase 1](docs/results/2026-06-02-phase1-results.md) · [Phase 2](docs/results/2026-06-04-phase2-results.md)

## 범위

- **Phase 1**: 3-way 비교 + 부하 하니스 + 정합성 오라클 + 커넥션 풀 노브.
- **Phase 2**: bounded pool replenishment + 격리수준 gap-lock 데드락(E1b 결정적 재현) + bounded pool 정합성(E2). (E3 lock-ordering은 미구현.)
