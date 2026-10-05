package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration.SuperuserDatabase;
import java.util.Properties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A {@link JdbcClient} as the container's superuser on the context's own database, for test housekeeping the runtime
 * role is (deliberately) not allowed to do: deleting settlement and credit-note rows between tests (ADR-0024). The
 * search path is {@code seller_api}, as for the application.
 */
public final class SuperuserJdbc {

    private SuperuserJdbc() {}

    public static JdbcClient of(SuperuserDatabase database) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", "seller_api");
        dataSource.setConnectionProperties(properties);
        return JdbcClient.create(dataSource);
    }

    /** Empties the outbox, the settlement book and the credit notes. */
    public static void reset(JdbcClient superuser) {
        superuser.sql("DELETE FROM event_publication").update();
        superuser.sql("DELETE FROM settlement").update();
        superuser.sql("DELETE FROM credit_note").update();
    }
}
