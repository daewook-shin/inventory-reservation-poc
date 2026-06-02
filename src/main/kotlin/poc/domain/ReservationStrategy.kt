package poc.domain

interface ReservationStrategy {
    fun name(): String
    /** 이 전략의 모든 상태를 비우고 주어진 아이템들을 초기 재고로 세팅한다. */
    fun reset(items: List<ItemSpec>)
    fun reserve(itemId: Long, locationId: Long, qty: Int): ReservationResult
    fun claim(reservationId: String)
    fun release(reservationId: String)
    fun snapshot(itemId: Long, locationId: Long): InventorySnapshot
}

data class ItemSpec(val itemId: Long, val locationId: Long, val totalStock: Int)
