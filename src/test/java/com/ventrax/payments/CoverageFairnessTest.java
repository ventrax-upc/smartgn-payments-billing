package com.ventrax.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.ventrax.payments.adapter.CoverageDelivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class CoverageFairnessTest {
    @Test
    void retryingOldEventsCannotStarveNewCoverage() throws Exception {
        String url = System.getenv("TEST_DB_URL"),
                password = System.getenv("TEST_DB_PASSWORD"),
                schema = "coverage_" + UUID.randomUUID().toString().replace("-", "");
        var flyway =
                org.flywaydb.core.Flyway.configure()
                        .dataSource(url, "postgres", password)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .load();
        flyway.migrate();
        var db =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                url + "?currentSchema=" + schema, "postgres", password));
        UUID good = UUID.randomUUID();
        for (int i = 0; i < 12; i++)
            db.update(
                    "insert into"
                        + " outbox(id,event_type,aggregate_id,payload,correlation_id,created_at,support_available_at)"
                        + " values(?,'CoverageCalculated',?,'{}',?,now()-interval '2"
                        + " days',now()-interval '1 day')",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        db.update(
                "insert into outbox(id,event_type,aggregate_id,payload,correlation_id)"
                    + " values(?,'CoverageCalculated',?,'{}',?)",
                good,
                UUID.randomUUID(),
                UUID.randomUUID());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/events",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(body.contains(good.toString()) ? 200 : 503, -1);
                    exchange.close();
                });
        server.start();
        try {
            var worker =
                    new CoverageDelivery(
                            db, "http://127.0.0.1:" + server.getAddress().getPort(), "test");
            for (int i = 0; i < 14; i++) {
                worker.deliver();
                db.update("update outbox set support_available_at=now() where support_attempts>0");
            }
            assertNotNull(
                    db.queryForObject(
                            "select support_delivered_at from outbox where id=?",
                            java.sql.Timestamp.class,
                            good));
        } finally {
            server.stop(0);
            new JdbcTemplate(new DriverManagerDataSource(url, "postgres", password))
                    .execute("drop schema " + schema + " cascade");
        }
    }
}
