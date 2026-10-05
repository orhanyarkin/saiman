package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.SuperuserJdbc;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0024: the running service connects as {@code seller_api_app}, which may do the DML its code needs and nothing
 * more. In particular it can not switch triggers off, rewrite or delete a credit note (the ledger's corroboration
 * evidence), delete a settlement, run DDL or touch Flyway's history.
 */
class RuntimeRoleGrantsTests extends SettlementTestBase {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static final String KEY =
            "eip155:84532:0x036cbd53842c5426634e7929541ec2318f3dcf7e:0x" + "33".repeat(20) + ":0x" + "44".repeat(32);

    @Test
    void theApplicationRunsAsTheAppRole() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("seller_api_app");
        assertThat(jdbc.sql("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    @Test
    void theRuntimeRoleCanNotDisableTriggersOrReplicationRules() {
        assertDenied("SET session_replication_role = replica");
        assertDenied("ALTER TABLE credit_note DISABLE TRIGGER ALL");
        assertDenied("ALTER TABLE settlement DISABLE TRIGGER ALL");
        assertDenied("ALTER TABLE event_publication DISABLE TRIGGER ALL");
    }

    @Test
    void creditNotesAreAppendOnlyForTheRuntimeRole() {
        insertCreditNote();

        assertDenied("UPDATE credit_note SET amount_atomic = 1");
        assertDenied("DELETE FROM credit_note");
        assertDenied("TRUNCATE credit_note");
        assertThat(jdbc.sql("SELECT count(*) FROM credit_note")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // The superuser (db-init, ledger-tamper-demo) still can.
        JdbcClient superuser = SuperuserJdbc.of(superuserDatabase);
        assertThat(superuser.sql("DELETE FROM credit_note").update()).isEqualTo(1);
    }

    @Test
    void settlementsCanBeUpgradedButNotDeleted() {
        jdbc.sql("""
                        INSERT INTO settlement (payment_key, amount_atomic, pay_to, payer, outcome, reason_code)
                        VALUES (:key, 10000, :payTo, :payer, 'SETTLE_FAILED', 'x')
                        """)
                .param("key", KEY)
                .param("payTo", PAY_TO)
                .param("payer", "0x" + "33".repeat(20))
                .update();
        assertThat(jdbc.sql("UPDATE settlement SET outcome = 'SETTLED', reason_code = NULL")
                        .update())
                .isEqualTo(1);

        assertDenied("DELETE FROM settlement");
        assertDenied("TRUNCATE settlement");
    }

    @Test
    void noDdlAndNoFlywayHistory() {
        assertDenied("SELECT count(*) FROM flyway_schema_history");
        assertDenied("CREATE TABLE intruder (id int)");
        assertDenied("ALTER TABLE credit_note DROP CONSTRAINT credit_note_pkey");
        assertDenied("DROP TABLE settlement");
    }

    private void assertDenied(String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).update())
                .as(sql)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).as(sql).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "none";
    }

    private void insertCreditNote() {
        jdbc.sql("""
                        INSERT INTO credit_note
                            (payment_key, tx_hash, amount_atomic, pay_to, payer, http_status, reason_code)
                        VALUES (:key, :tx, 20000, :payTo, :payer, 503, 'handler_server_error')
                        """)
                .param("key", KEY)
                .param("tx", "0x" + "ab".repeat(32))
                .param("payTo", PAY_TO)
                .param("payer", "0x" + "33".repeat(20))
                .update();
    }
}
