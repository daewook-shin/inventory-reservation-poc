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
                   LIMIT ? FOR UPDATE SKIP LOCKED""",
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
