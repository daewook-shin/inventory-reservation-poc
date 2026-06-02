package poc.bench

object ResultPrinter {
    fun toMarkdown(results: List<BenchResult>): String {
        val sb = StringBuilder()
        sb.appendLine("| strategy | scenario | throughput/s | p50(µs) | p95(µs) | p99(µs) | success | soldOut | errors | oversell | conserved |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in results) {
            sb.appendLine(
                "| ${r.strategy} | ${r.scenario} | ${"%.1f".format(r.throughputPerSec)} | " +
                    "${r.latency.p50 / 1000} | ${r.latency.p95 / 1000} | ${r.latency.p99 / 1000} | " +
                    "${r.success} | ${r.soldOut} | ${r.errors} | ${r.oversell} | ${r.conserved} |",
            )
        }
        return sb.toString()
    }
}
