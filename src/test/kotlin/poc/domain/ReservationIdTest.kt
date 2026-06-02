package poc.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReservationIdTest {
    @Test
    fun `encode then parse roundtrips item and location`() {
        val id = ReservationId.create(itemId = 7, locationId = 3)
        val parsed = ReservationId.parse(id)
        assertEquals(7L, parsed.itemId)
        assertEquals(3L, parsed.locationId)
    }
}
