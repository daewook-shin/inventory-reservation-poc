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
