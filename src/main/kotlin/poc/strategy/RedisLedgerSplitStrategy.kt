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
