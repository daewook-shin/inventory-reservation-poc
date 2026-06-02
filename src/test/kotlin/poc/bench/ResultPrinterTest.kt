package poc.bench

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultPrinterTest {
    @Test
    fun `table contains strategy name and oversell column`() {
        val results = listOf(
            BenchResult("mysql-skip-locked", "LAST_UNITS", 1234.5, LatencyStats(10, 20, 30, 100), 10, 190, 0, 0, true),
        )
        val table = ResultPrinter.toMarkdown(results)
        assertTrue(table.contains("mysql-skip-locked"))
        assertTrue(table.contains("oversell"))
    }
}
