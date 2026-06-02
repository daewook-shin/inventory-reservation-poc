package poc.bench

data class ClientResult(
    var success: Long = 0,
    var soldOut: Long = 0,
    var errors: Long = 0,
    val latenciesNs: MutableList<Long> = mutableListOf(),
)

data class LatencyStats(val p50: Long, val p95: Long, val p99: Long, val count: Int) {
    companion object {
        fun from(latenciesNs: List<Long>): LatencyStats {
            if (latenciesNs.isEmpty()) return LatencyStats(0, 0, 0, 0)
            val sorted = latenciesNs.sorted()
            fun pct(p: Double): Long {
                val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
                return sorted[rank - 1]
            }
            return LatencyStats(pct(50.0), pct(95.0), pct(99.0), sorted.size)
        }
    }
}

fun List<ClientResult>.merge(): ClientResult {
    val out = ClientResult()
    for (r in this) {
        out.success += r.success
        out.soldOut += r.soldOut
        out.errors += r.errors
        out.latenciesNs.addAll(r.latenciesNs)
    }
    return out
}
