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
