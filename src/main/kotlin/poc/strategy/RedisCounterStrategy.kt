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
