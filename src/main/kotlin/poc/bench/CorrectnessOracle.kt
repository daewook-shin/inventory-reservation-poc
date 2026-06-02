package poc.bench

import poc.domain.InventorySnapshot

data class OracleReport(val conserved: Boolean, val oversell: Long, val available: Long, val soldOut: Boolean = false)

object CorrectnessOracle {
    /** 보존 법칙: sold + reserved + available == total. 초과분(oversell)은 0 이상. */
    fun check(snap: InventorySnapshot, total: Long): OracleReport {
        val accounted = snap.sold + snap.reserved + snap.available
        val oversell = (snap.sold + snap.reserved - total).coerceAtLeast(0)
        return OracleReport(conserved = accounted == total, oversell = oversell, available = snap.available)
    }
}
