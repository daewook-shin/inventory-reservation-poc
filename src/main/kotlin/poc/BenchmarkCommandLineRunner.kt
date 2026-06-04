package poc

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import poc.bench.BenchConfig
import poc.bench.BenchmarkRunner
import poc.bench.BenchResult
import poc.bench.ResultPrinter
import poc.bench.Scenario
import poc.domain.ReservationStrategy
import poc.experiment.ExperimentPrinter
import poc.experiment.ExperimentRunner
import poc.experiment.Tx

@Component
@Profile("!test")
class BenchmarkCommandLineRunner(
    private val strategies: List<ReservationStrategy>,
    private val experimentRunner: ExperimentRunner,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val clients = args.getOptionValues("clients")?.firstOrNull()?.toInt() ?: 200

        // --experiment=E1|E2|all 이면 Phase 2 실험을 돌리고 종료
        val experiment = args.getOptionValues("experiment")?.firstOrNull()
        if (experiment != null) {
            runExperiments(experiment, clients)
            context.close()
            return
        }

        // 기존 Phase 1 벤치마크
        val strategyFilter = args.getOptionValues("strategy")?.firstOrNull()
        val scenarioFilter = args.getOptionValues("scenario")?.firstOrNull()
        val selectedStrategies = strategies.filter { strategyFilter == null || it.name() == strategyFilter }
        val selectedScenarios = Scenario.entries.filter { scenarioFilter == null || it.name == scenarioFilter }

        val runner = BenchmarkRunner(BenchConfig(clients = clients))
        val results = mutableListOf<BenchResult>()
        for (strategy in selectedStrategies) {
            for (scenario in selectedScenarios) {
                println(">>> ${strategy.name()} / ${scenario.name} (clients=$clients) ...")
                results.add(runner.run(strategy, scenario))
            }
        }
        println("\n## 결과\n")
        println(ResultPrinter.toMarkdown(results))
        context.close()
    }

    private fun runExperiments(which: String, clients: Int) {
        if (which == "E1" || which == "all") {
            println(">>> E1 격리수준 데드락 (REPEATABLE_READ) ...")
            val rr = experimentRunner.runE1(Tx.REPEATABLE_READ, clients = clients, rounds = 10)
            println(">>> E1 격리수준 데드락 (READ_COMMITTED) ...")
            val rc = experimentRunner.runE1(Tx.READ_COMMITTED, clients = clients, rounds = 10)
            println("\n${ExperimentPrinter.e1Markdown(rrStats = rr, rcStats = rc)}")
            println(">>> E1b gap-lock 데드락 최소 재현 ...")
            val demoRr = experimentRunner.runGapLockDemo(Tx.REPEATABLE_READ)
            val demoRc = experimentRunner.runGapLockDemo(Tx.READ_COMMITTED)
            println("\n${ExperimentPrinter.e1DemoMarkdown(demoRr, demoRc)}")
        }
        if (which == "E2" || which == "all") {
            println(">>> E2 bounded pool 정합성 ...")
            val r = experimentRunner.runE2(clients = clients, rounds = 20, ledgerTotal = 5000, poolCap = 1000)
            println("\n${ExperimentPrinter.e2Markdown(r)}")
        }
    }
}
