package poc.experiment

import java.util.concurrent.atomic.AtomicLong
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** InnoDB 락 충돌 카운트. 여러 코루틴에서 안전하게 증가시킨다. */
class DeadlockStats {
    val deadlocks = AtomicLong()          // MySQL 1213
    val lockWaitTimeouts = AtomicLong()   // MySQL 1205
    val otherErrors = AtomicLong()
    val successes = AtomicLong()
    val soldOut = AtomicLong()
}

/** 원장 + 풀 상태 스냅샷. */
data class LedgerSnapshot(val total: Long, val sold: Long, val reserved: Long, val pool: Long) {
    val oversell: Long get() = (sold + reserved - total).coerceAtLeast(0)
    val conserved: Boolean get() = sold + reserved <= total
}

/** 주어진 격리수준의 TransactionTemplate을 만든다. */
object Tx {
    fun template(tm: PlatformTransactionManager, isolation: Int): TransactionTemplate =
        TransactionTemplate(tm).apply { isolationLevel = isolation }

    const val READ_COMMITTED = TransactionDefinition.ISOLATION_READ_COMMITTED
    const val REPEATABLE_READ = TransactionDefinition.ISOLATION_REPEATABLE_READ
}
