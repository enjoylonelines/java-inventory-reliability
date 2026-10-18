package com.inventory.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A separate JVM used only by the opt-in crash experiment. */
public final class OracleCrashWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !java.util.Set.of("INVENTORY_CRASH_TEST", "INVENTORY_CRASH_TEST2").contains(
                String.valueOf(System.getenv("ORACLE_CRASH_USER")).toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("dedicated crash schema required");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("ORACLE_TEST_URL"));
        config.setUsername(System.getenv("ORACLE_CRASH_USER"));
        config.setPassword(System.getenv("ORACLE_TEST_PASSWORD"));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        try (HikariDataSource source = new HikariDataSource(config)) {
            Configuration mybatis = new Configuration();
            mybatis.addMapper(OracleOutboxMapper.class);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(mybatis);
            OracleOutboxMapper mapper = new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject()))
                .getMapper(OracleOutboxMapper.class);
            OracleOutboxProcessor worker = new OracleOutboxProcessor(mapper,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                new OracleMockHttpDelivery(args[0], new ObjectMapper()), 5, 5);
            if (worker.processOne() != OracleOutboxProcessor.Outcome.SENT) System.exit(2);
        }
    }
}
