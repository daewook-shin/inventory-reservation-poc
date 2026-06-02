package poc

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import poc.bench.BenchConfig
import poc.bench.BenchmarkRunner
import poc.bench.BenchResult
import poc.bench.ResultPrinter
import poc.bench.Scenario
import poc.domain.ReservationStrategy

@Component
class BenchmarkCommandLineRunner(
    private val strategies: List<ReservationStrategy>,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        // --strategy=mysql-skip-locked (없으면 전체), --scenario=LAST_UNITS (없으면 전체)
        val strategyFilter = args.getOptionValues("strategy")?.firstOrNull()
        val scenarioFilter = args.getOptionValues("scenario")?.firstOrNull()
        val clients = args.getOptionValues("clients")?.firstOrNull()?.toInt() ?: 200

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

        // 콘솔 전용 실행 후 종료
        context.close()
    }
}
