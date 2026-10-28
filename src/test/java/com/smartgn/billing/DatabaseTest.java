package com.smartgn.billing;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

class DatabaseTest {
    static JdbcTemplate db;

    @BeforeAll
    static void migrate() {
        String url = System.getenv("TEST_DB_URL");
        assertNotNull(url, "Run scripts/test.ps1 to supply isolated PostgreSQL");
        var ds =
                new DriverManagerDataSource(
                        url + "?currentSchema=billing", "postgres", "local-test-password");
        Flyway.configure()
                .dataSource(ds)
                .schemas("billing")
                .defaultSchema("billing")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        db.execute("SET search_path TO billing");
    }

    @Test
    void hundredPaymentRetriesHaveOneFinancialEffect() {
        UUID order = UUID.randomUUID();
        var amount = new java.math.BigDecimal("250.00");
        db.update(
                "INSERT INTO billing.payment_orders(id,reason,amount,idempotency_key,fingerprint)"
                    + " VALUES (?,'INITIAL',250,?,?)",
                order,
                order.toString(),
                order.toString());
        var gateway = new com.smartgn.billing.adapter.LocalPaymentGateway();
        gateway.fixture("verified-" + order, order, "CONFIRMED", amount);
        var service =
                new com.smartgn.billing.application.BillingService(
                        db,
                        gateway,
                        "http://127.0.0.1:1",
                        "x".repeat(40),
                        new java.math.BigDecimal("200.00"),
                        new java.math.BigDecimal("50.00"),
                        new java.math.BigDecimal("35.00"));
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                db.getDataSource()));
        for (int i = 0; i < 100; i++)
            tx.execute(status -> service.confirm(order, "verified-" + order));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM billing.payment_transactions WHERE order_id=?",
                        Integer.class,
                        order));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM billing.billing_documents WHERE order_id=?",
                        Integer.class,
                        order));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM billing.outbox WHERE aggregate_id=?",
                        Integer.class,
                        order));
    }

    @Test
    void delegatedIdentityCannotManageFinancialResources() {
        var service =
                new com.smartgn.billing.application.BillingService(
                        db,
                        new com.smartgn.billing.adapter.LocalPaymentGateway(),
                        "http://127.0.0.1:1",
                        "x".repeat(40),
                        new java.math.BigDecimal("200.00"),
                        new java.math.BigDecimal("50.00"),
                        new java.math.BigDecimal("35.00"));
        var actor =
                new com.smartgn.billing.application.IdentityPort.Account(
                        UUID.randomUUID(), "delegate@example.test", "USER", "DELEGATED", "ACTIVE");
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.history(actor, 0, 20));
    }

    @Test
    void unverifiedPaymentCannotSucceed() {
        var gateway = new com.smartgn.billing.adapter.LocalPaymentGateway();
        UUID order = UUID.randomUUID();
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> gateway.verify("unknown", order, new java.math.BigDecimal("250.00")));
        gateway.fixture("test", order, "UNKNOWN", new java.math.BigDecimal("250.00"));
        assertEquals(
                "UNKNOWN",
                gateway.verify("test", order, new java.math.BigDecimal("250.00")).status());
        gateway.fixture("test", order, "CONFIRMED", new java.math.BigDecimal("250.00"));
        assertEquals(
                "CONFIRMED",
                gateway.verify("test", order, new java.math.BigDecimal("250.00")).status());
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () ->
                        gateway.verify(
                                "test", UUID.randomUUID(), new java.math.BigDecimal("250.00")));
    }

    @Test
    void migrationCreatesIndependentSchema() {
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM billing.flyway_schema_history WHERE success AND"
                            + " version IS NOT NULL",
                        Integer.class));
    }

    @Test
    void duplicateProviderReferenceRejected() {
        UUID order = UUID.randomUUID();
        db.update(
                "INSERT INTO billing.payment_orders(id,reason,amount,idempotency_key,fingerprint)"
                    + " VALUES (?,'INITIAL',250,?,?)",
                order,
                order.toString(),
                order.toString());
        db.update(
                "INSERT INTO billing.payment_transactions VALUES (?,?,?,'CONFIRMED',250,now())",
                UUID.randomUUID(),
                order,
                "ref-" + order);
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "INSERT INTO billing.payment_transactions VALUES"
                                    + " (?,?,?,'CONFIRMED',250,now())",
                                UUID.randomUUID(),
                                order,
                                "ref-" + order));
    }

    @Test
    void negativeBalanceCannotBecomeOrder() {
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "INSERT INTO"
                                    + " billing.payment_orders(id,reason,amount,idempotency_key,fingerprint)"
                                    + " VALUES (?,'INITIAL',-1,?,?)",
                                UUID.randomUUID(),
                                UUID.randomUUID().toString(),
                                "x"));
    }

    @Test
    void fiscalSequenceUniqueAcrossHundredRequests() {
        Set<Long> numbers = new HashSet<>();
        for (int i = 0; i < 100; i++)
            numbers.add(db.queryForObject("SELECT nextval('billing.fiscal_number')", Long.class));
        assertEquals(100, numbers.size());
    }
}
