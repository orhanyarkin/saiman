package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.evmrpc.BaseSepoliaUsdc;
import io.github.orhanyarkin.saiman.evmrpc.ChainProperties;
import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.journal.JournalRepository;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The single-runner lease when its unlock fails: the connection is aborted (Postgres ends the session and drops
 * the advisory lock) instead of going back to the pool still holding it. Shares the reconciliation context.
 */
@LedgerIntegrationTest
@TestPropertySource(properties = "saiman.test.context=reconciliation")
class ReconciliationLeaseTests {

    @Autowired
    private ObjectProvider<BaseSepoliaUsdc> chain;

    @Autowired
    private ObjectProvider<ChainProperties> chainProperties;

    @Autowired
    private ReconciliationProperties properties;

    @Autowired
    private ReconciliationRepository repository;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private JournalRepository journal;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Clock clock;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ObjectProvider<ObservationRegistry> observations;

    @Autowired
    private ReconciliationService service;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void failedUnlockAbortsTheConnectionSoTheLockIsGoneAndTheNextRunStarts() {
        AtomicInteger failedUnlocks = new AtomicInteger();
        var failingUnlock = new ReconciliationService(
                chain,
                chainProperties,
                properties,
                repository,
                payments,
                journal,
                events,
                transactionManager,
                unlockFailing(dataSource, failedUnlocks),
                clock,
                meters,
                observations);

        assertThat(failingUnlock.runNow()).isPresent();

        assertThat(failedUnlocks).hasValue(1);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(advisoryLocksHeld()).isZero());
        assertThat(service.runNow()).as("the next run is not refused").isPresent();
        assertThat(failingUnlock.runNow())
                .as("the failing service's in-process flag was reset too")
                .isPresent();
    }

    /** Sessions holding the runner's advisory lock (a bigint key: high and low 32 bits, objsubid 1). */
    private long advisoryLocksHeld() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_locks
                         WHERE locktype = 'advisory' AND classid = :high AND objid = :low AND objsubid = 1
                        """)
                .param("high", ReconciliationService.LOCK_KEY >>> 32)
                .param("low", ReconciliationService.LOCK_KEY & 0xFFFFFFFFL)
                .query(Long.class)
                .single();
    }

    /** A data source whose connections throw on {@code pg_advisory_unlock} (everything else is delegated). */
    private static DataSource unlockFailing(DataSource real, AtomicInteger failures) {
        InvocationHandler dataSource = (proxy, method, args) -> {
            Object result = invoke(real, method, args);
            return result instanceof Connection connection ? unlockFailing(connection, failures) : result;
        };
        return (DataSource) Proxy.newProxyInstance(
                ReconciliationLeaseTests.class.getClassLoader(), new Class<?>[] {DataSource.class}, dataSource);
    }

    private static Connection unlockFailing(Connection real, AtomicInteger failures) {
        InvocationHandler connection = (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement")
                    && args != null
                    && args[0] instanceof String sql
                    && sql.contains("pg_advisory_unlock")) {
                failures.incrementAndGet();
                throw new SQLException("simulated unlock failure", "08006");
            }
            return invoke(real, method, args);
        };
        return (Connection) Proxy.newProxyInstance(
                ReconciliationLeaseTests.class.getClassLoader(), new Class<?>[] {Connection.class}, connection);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
