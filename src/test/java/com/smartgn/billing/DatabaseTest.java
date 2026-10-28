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
        var gateway = new com.smartgn.billing.adapter.LocalPaymentGateway(db);
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
                        new com.smartgn.billing.adapter.LocalPaymentGateway(db),
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
        var gateway = new com.smartgn.billing.adapter.LocalPaymentGateway(db);
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
                3,
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

    private com.smartgn.billing.application.BillingService service() {
        return new com.smartgn.billing.application.BillingService(
                db,
                new com.smartgn.billing.adapter.LocalPaymentGateway(db),
                "http://127.0.0.1:1",
                "x".repeat(40),
                new java.math.BigDecimal("200.00"),
                new java.math.BigDecimal("50.00"),
                new java.math.BigDecimal("35.00"));
    }

    private org.springframework.transaction.support.TransactionTemplate tx() {
        return new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        db.getDataSource()));
    }

    @Test
    void concurrentConfirmationsHaveOneEffect() throws Exception {
        UUID order = UUID.randomUUID();
        String ref = "concurrent-" + order;
        db.update(
                "INSERT INTO billing.payment_orders(id,reason,amount,idempotency_key,fingerprint)"
                        + " VALUES (?,'INITIAL',250,?,?)",
                order,
                order.toString(),
                order.toString());
        new com.smartgn.billing.adapter.LocalPaymentGateway(db)
                .fixture(ref, order, "CONFIRMED", new java.math.BigDecimal("250.00"));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int n = 0; n < 16; n++)
                tasks.add(pool.submit(() -> tx().execute(status -> service().confirm(order, ref))));
            for (var task : tasks) task.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM billing.billing_documents WHERE order_id=?",
                        Integer.class,
                        order));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM billing.payment_transactions WHERE order_id=?",
                        Integer.class,
                        order));
    }

    @Test
    void historicalCoverageIsImmutableAndEventConflictRollsBack() {
        UUID account = UUID.randomUUID(),
                order = UUID.randomUUID(),
                visit = UUID.randomUUID(),
                meter = UUID.randomUUID(),
                event = UUID.randomUUID();
        var at = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update(
                "INSERT INTO"
                    + " billing.payment_orders(id,account_id,reason,amount,idempotency_key,fingerprint,status)"
                    + " VALUES (?,?,'PRO',35,?,?,'CONFIRMED')",
                order,
                account,
                order.toString(),
                order.toString());
        db.update(
                "INSERT INTO billing.subscriptions(account_id,installed_at) VALUES"
                        + " (?,now()-interval '2 days')",
                account);
        db.update(
                "INSERT INTO billing.subscription_periods VALUES (?,?,?,now()-interval '1"
                        + " hour',now()+interval '1 hour')",
                UUID.randomUUID(),
                account,
                order);
        tx().execute(
                        status -> {
                            service()
                                    .visit(
                                            event,
                                            visit,
                                            account,
                                            meter,
                                            at,
                                            new java.math.BigDecimal("72.00"));
                            return null;
                        });
        db.update(
                "UPDATE billing.subscription_periods SET ends_at=? WHERE account_id=?",
                java.sql.Timestamp.from(at.minusSeconds(1)),
                account);
        tx().execute(
                        status -> {
                            service()
                                    .visit(
                                            event,
                                            visit,
                                            account,
                                            meter,
                                            at,
                                            new java.math.BigDecimal("72.00"));
                            return null;
                        });
        assertTrue(
                db.queryForObject(
                        "SELECT covered FROM billing.coverage_decisions WHERE visit_id=?",
                        Boolean.class,
                        visit));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () ->
                        tx().execute(
                                        status -> {
                                            service()
                                                    .visit(
                                                            event,
                                                            visit,
                                                            account,
                                                            meter,
                                                            at,
                                                            new java.math.BigDecimal("73.00"));
                                            return null;
                                        }));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM billing.integration_inbox WHERE event_id=?",
                        Integer.class,
                        event));
    }

    @Test
    void unrelatedAccountsCannotDownloadOrReadEachOthersOrders() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " billing.payment_orders(id,account_id,reason,amount,idempotency_key,fingerprint)"
                    + " VALUES (?,?,'TECHNICAL',40,?,?)",
                id,
                owner,
                id.toString(),
                id.toString());
        var actor =
                new com.smartgn.billing.application.IdentityPort.Account(
                        other, "other@example.test", "USER", "PRINCIPAL", "ACTIVE");
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service().safeOrder(id, null, actor));
        assertTrue(service().history(actor, 0, 50).isEmpty());
    }

    @Test
    void providerEvidenceSurvivesAdapterRecreation() {
        UUID order = UUID.randomUUID();
        String reference = "restart-" + order;
        new com.smartgn.billing.adapter.LocalPaymentGateway(db)
                .fixture(reference, order, "UNKNOWN", new java.math.BigDecimal("35.00"));
        assertEquals(
                "UNKNOWN",
                new com.smartgn.billing.adapter.LocalPaymentGateway(db)
                        .verify(reference, order, new java.math.BigDecimal("35.00"))
                        .status());
    }

    @Test
    void upgradingExistingFiscalRecordsPreservesDailySequence() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var ds =
                new DriverManagerDataSource(
                        System.getenv("TEST_DB_URL") + "?currentSchema=" + schema,
                        "postgres",
                        "local-test-password");
        var old =
                Flyway.configure()
                        .dataSource(ds)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .target("2")
                        .load();
        old.migrate();
        var upgrade = new JdbcTemplate(ds);
        UUID order = UUID.randomUUID(), doc = UUID.randomUUID();
        upgrade.update(
                "INSERT INTO payment_orders(id,reason,amount,idempotency_key,fingerprint) VALUES"
                    + " (?,'INITIAL',250,?,?)",
                order,
                order.toString(),
                order.toString());
        upgrade.update(
                "INSERT INTO billing_documents(id,order_id,series,number) VALUES (?,?,'B001',7)",
                doc,
                order);
        upgrade.update(
                "INSERT INTO fiscal_summaries(id,document_id,name,xml,status) VALUES"
                    + " (?,?,'20100066603-RC-20261028-7','<test/>','LOCAL_ACCEPTED')",
                UUID.randomUUID(),
                doc);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
        assertEquals(
                7,
                upgrade.queryForObject(
                        "SELECT value FROM fiscal_daily_sequences WHERE issue_day='2026-10-28'",
                        Integer.class));
    }
}
