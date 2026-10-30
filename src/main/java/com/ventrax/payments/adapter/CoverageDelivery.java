package com.ventrax.payments.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

@Component
public class CoverageDelivery {
    private final JdbcTemplate db;
    private final RestClient support;
    private final boolean enabled;

    public CoverageDelivery(
            JdbcTemplate db,
            @Value("${smartgn.support-url:}") String url,
            @Value("${smartgn.internal-token}") String token) {
        this.db = db;
        enabled = !url.isBlank();
        var factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(2))
                                .build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(3));
        support =
                RestClient.builder()
                        .baseUrl(enabled ? url : "http://127.0.0.1:1")
                        .requestFactory(factory)
                        .defaultHeader("Authorization", "Bearer " + token)
                        .build();
    }

    @Scheduled(fixedDelay = 3000)
    public void deliver() {
        if (!enabled) return;
        UUID lease = UUID.randomUUID();
        var rows =
                db.queryForList(
                        "UPDATE outbox SET support_lease_id=?,support_lease_until=now()+interval"
                            + " '20 seconds' WHERE id=(SELECT id FROM outbox WHERE"
                            + " event_type='CoverageCalculated' AND support_delivered_at IS NULL"
                            + " AND support_available_at<=now() AND (support_lease_until IS NULL OR"
                            + " support_lease_until<now()) ORDER BY support_available_at,created_at"
                            + " LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *",
                        lease);
        for (var row : rows)
            try {
                var envelope =
                        Map.of(
                                "event_id",
                                row.get("id"),
                                "event_type",
                                row.get("event_type"),
                                "aggregate_id",
                                row.get("aggregate_id"),
                                "correlation_id",
                                row.get("correlation_id"),
                                "payload",
                                new tools.jackson.databind.ObjectMapper()
                                        .readValue(row.get("payload").toString(), Map.class));
                support.post()
                        .uri("/internal/v1/events")
                        .body(envelope)
                        .retrieve()
                        .toBodilessEntity();
                db.update(
                        "UPDATE outbox SET support_delivered_at=now(),support_lease_until=NULL"
                                + " WHERE id=? AND support_lease_id=?",
                        row.get("id"),
                        lease);
            } catch (Exception retry) {
                db.update(
                        "UPDATE outbox SET"
                            + " support_attempts=support_attempts+1,support_available_at=now()+interval"
                            + " '30 seconds',support_lease_until=NULL WHERE id=? AND"
                            + " support_lease_id=?",
                        row.get("id"),
                        lease);
            }
    }
}
