package poc.config

import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class JdbcConfig {
    @Bean
    fun readCommittedTxTemplate(ds: DataSource): TransactionTemplate {
        val tm = DataSourceTransactionManager(ds)
        val t = TransactionTemplate(tm)
        t.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        return t
    }
}
