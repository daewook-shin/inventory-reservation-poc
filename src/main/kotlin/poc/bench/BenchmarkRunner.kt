package poc.bench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import poc.domain.ReservationOutcome
import poc.domain.ReservationStrategy

data class BenchConfig(val clients: Int = 200, val warmupRounds: Int = 2, val measuredRounds: Int = 5)

data class BenchResult(
    val strategy: String,
    val scenario: String,
    val throughputPerSec: Double,
    val latency: LatencyStats,
    val success: Long,
    val soldOut: Long,
    val errors: Long,
    val oversell: Long,
    val conserved: Boolean,
)

class BenchmarkRunner(private val config: BenchConfig = BenchConfig()) {

    fun run(strategy: ReservationStrategy, scenario: Scenario): BenchResult {
        strategy.reset(scenario.items())

        // 워밍업 (측정 제외)
        repeat(config.warmupRounds) { oneRound(strategy, scenario) }
        strategy.reset(scenario.items())

        // 측정
        val startNs = System.nanoTime()
        val rounds = (1..config.measuredRounds).map { oneRound(strategy, scenario) }
        val wallSec = (System.nanoTime() - startNs) / 1_000_000_000.0
        val merged = rounds.flatten().merge()

        // 정합성: 시나리오의 모든 아이템 합산
        var totalOversell = 0L
        var allConserved = true
        for (item in scenario.items()) {
            val snap = strategy.snapshot(item.itemId, scenario.locationId)
            val report = CorrectnessOracle.check(snap, item.totalStock.toLong())
            totalOversell += report.oversell
            if (!report.conserved) allConserved = false
        }

        return BenchResult(
            strategy = strategy.name(),
            scenario = scenario.name,
            throughputPerSec = merged.success / wallSec,
            latency = LatencyStats.from(merged.latenciesNs),
            success = merged.success,
            soldOut = merged.soldOut,
            errors = merged.errors,
            oversell = totalOversell,
            conserved = allConserved,
        )
    }

    private fun oneRound(strategy: ReservationStrategy, scenario: Scenario): List<ClientResult> = runBlocking {
        (0 until config.clients).map { idx ->
            async(Dispatchers.IO) {
                val res = ClientResult()
                val itemId = scenario.pickItem(idx)
                val t0 = System.nanoTime()
                try {
                    val r = strategy.reserve(itemId, scenario.locationId, 1)
                    res.latenciesNs.add(System.nanoTime() - t0)
                    when (r.outcome) {
                        ReservationOutcome.SUCCESS -> {
                            res.success++
                            when (scenario.afterReserve()) {
                                AfterReserve.CLAIM -> strategy.claim(r.reservationId!!)
                                AfterReserve.RELEASE -> strategy.release(r.reservationId!!)
                            }
                        }
                        ReservationOutcome.SOLD_OUT -> res.soldOut++
                    }
                } catch (e: Exception) {
                    res.errors++
                }
                res
            }
        }.awaitAll()
    }
}
