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
