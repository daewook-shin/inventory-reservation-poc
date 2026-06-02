package poc.bench

import poc.domain.ItemSpec

/**
 * 각 시나리오는 아이템 레이아웃과 클라이언트의 1회 동작(action)을 정의한다.
 * action 은 reserve 결과에 따라 claim/release 를 호출해 시나리오 성격을 만든다.
 */
enum class Scenario(val baseItem: Long) {
    /** 1개 핫 아이템, 충분한 재고. reserve→즉시 release 로 재고를 순환시켜 순수 락 경합/처리량 측정. */
    HOT_SINGLE(1000) {
        override fun items() = listOf(ItemSpec(baseItem, 1, 1000))
        override fun pickItem(clientIndex: Int) = baseItem
        override fun afterReserve() = AfterReserve.RELEASE
    },
    /** 재고 10개에 다수 클라이언트. reserve→claim(반환 없음). oversell 검증의 핵심. */
    LAST_UNITS(2000) {
        override fun items() = listOf(ItemSpec(baseItem, 1, 10))
        override fun pickItem(clientIndex: Int) = baseItem
        override fun afterReserve() = AfterReserve.CLAIM
    },
    /** 1000개 아이템에 분산, 각 50개 재고. 현실적 저경합 처리량. reserve→claim. */
    MIXED(3000) {
        override fun items() = (0 until 1000).map { ItemSpec(baseItem + it, 1, 50) }
        override fun pickItem(clientIndex: Int) = baseItem + (clientIndex % 1000)
        override fun afterReserve() = AfterReserve.CLAIM
    };

    abstract fun items(): List<ItemSpec>
    abstract fun pickItem(clientIndex: Int): Long
    abstract fun afterReserve(): AfterReserve
    val locationId: Long get() = 1
}

enum class AfterReserve { CLAIM, RELEASE }
