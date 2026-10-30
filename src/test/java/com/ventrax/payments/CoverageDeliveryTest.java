package com.ventrax.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import com.ventrax.payments.adapter.CoverageDelivery;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.util.*;

class CoverageDeliveryTest {
    @Test
    void retriesWithStableEnvelopeAndIndependentReceipt() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(503);
        List<String> requests = new ArrayList<>();
        server.createContext(
                "/internal/v1/events",
                exchange -> {
                    assertEquals(
                            "Bearer service-token",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    requests.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(status.get(), -1);
                    exchange.close();
                });
        server.start();
        try {
            var db = mock(JdbcTemplate.class);
            UUID id = UUID.randomUUID(), visit = UUID.randomUUID(), correlation = UUID.randomUUID();
            when(db.queryForList(anyString(), any(UUID.class)))
                    .thenReturn(
                            List.of(
                                    Map.of(
                                            "id",
                                            id,
                                            "event_type",
                                            "CoverageCalculated",
                                            "aggregate_id",
                                            visit,
                                            "correlation_id",
                                            correlation,
                                            "payload",
                                            "{\"visitId\":\"" + visit + "\"}")));
            var worker =
                    new CoverageDelivery(
                            db,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "service-token");
            worker.deliver();
            verify(db, never()).update(contains("support_delivered_at=now"), any(), any());
            status.set(200);
            worker.deliver();
            verify(db).update(contains("support_delivered_at=now"), eq(id), any());
            assertEquals(2, requests.size());
            assertEquals(requests.get(0), requests.get(1));
            assertTrue(requests.get(0).contains(id.toString()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledDeliveryDoesNotReadDatabase() {
        var db = mock(JdbcTemplate.class);
        new CoverageDelivery(db, "", "token").deliver();
        verifyNoInteractions(db);
    }
}
