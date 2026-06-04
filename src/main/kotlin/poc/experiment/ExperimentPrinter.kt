package poc.experiment

object ExperimentPrinter {
    fun e1Markdown(rrStats: DeadlockStats, rcStats: DeadlockStats): String {
        val sb = StringBuilder()
        sb.appendLine("### E1 — 격리수준별 락 충돌")
        sb.appendLine("| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |")
        sb.appendLine("|---|---|---|---|---|---|")
        fun row(name: String, s: DeadlockStats) =
            "| $name | ${s.deadlocks.get()} | ${s.lockWaitTimeouts.get()} | ${s.otherErrors.get()} | ${s.successes.get()} | ${s.soldOut.get()} |"
        sb.appendLine(row("REPEATABLE_READ", rrStats))
        sb.appendLine(row("READ_COMMITTED", rcStats))
        return sb.toString()
    }

    fun e2Markdown(r: E2Report): String {
        val sb = StringBuilder()
        sb.appendLine("### E2 — Bounded pool 정합성")
        sb.appendLine("| total | successes(sold) | oversell | conserved | maxPoolObserved | finalPool |")
        sb.appendLine("|---|---|---|---|---|---|")
        sb.appendLine("| ${r.total} | ${r.successes} | ${r.oversell} | ${r.conserved} | ${r.maxPoolObserved} | ${r.finalSnapshot.pool} |")
        return sb.toString()
    }
}
