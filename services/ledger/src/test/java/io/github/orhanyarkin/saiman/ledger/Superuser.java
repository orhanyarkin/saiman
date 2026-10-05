package io.github.orhanyarkin.saiman.ledger;

import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration.SuperuserDatabase;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The container's superuser on this context's database, schema {@code ledger}, for tests that tamper on purpose
 * (ADR-0024): the service itself runs as {@code ledger_app}, which can't.
 */
public final class Superuser {

    private Superuser() {}

    /** A non-pooled data source; every connection uses {@code search_path = ledger}. */
    public static DataSource dataSource(SuperuserDatabase db) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", "ledger");
        dataSource.setConnectionProperties(properties);
        return dataSource;
    }

    public static JdbcClient jdbc(SuperuserDatabase db) {
        return JdbcClient.create(dataSource(db));
    }
}
