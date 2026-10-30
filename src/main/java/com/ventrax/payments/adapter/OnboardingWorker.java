package com.ventrax.payments.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

@Component
public class OnboardingWorker {
    private final JdbcTemplate db;
    private final RestClient iam, installation;

    public OnboardingWorker(
            JdbcTemplate db,
            @Value("${smartgn.iam-url}") String iamUrl,
            @Value("${smartgn.installation-url}") String instUrl,
            @Value("${smartgn.internal-token}") String token) {
        this.db = db;
        var f =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(2))
                                .build());
        f.setReadTimeout(java.time.Duration.ofSeconds(3));
        iam =
                RestClient.builder()
                        .requestInterceptor(new com.ventrax.payments.configuration.TraceHttp())
                        .baseUrl(iamUrl)
                        .requestFactory(f)
                        .defaultHeader("Authorization", "Bearer " + token)
                        .build();
        installation = iam.mutate().baseUrl(instUrl).build();
    }

    @Scheduled(fixedDelay = 3000)
    public void send() {
        UUID claim = UUID.randomUUID();
        var claimed =
                db.queryForList(
                        "UPDATE outbox SET lease_id=?,lease_until=now()+interval '30 seconds' WHERE"
                            + " id=(SELECT x.id FROM outbox x JOIN payment_orders o ON"
                            + " o.id=x.aggregate_id WHERE x.event_type='PaymentConfirmed' AND"
                            + " o.reason='INITIAL' AND x.delivered_at IS NULL AND"
                            + " x.available_at<=now() AND (x.lease_until IS NULL OR"
                            + " x.lease_until<now()) ORDER BY x.created_at LIMIT 1 FOR UPDATE OF x"
                            + " SKIP LOCKED) RETURNING id",
                        claim);
        var jobs = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var c : claimed)
            jobs.addAll(
                    db.queryForList(
                            "SELECT x.id AS event_id,x.correlation_id,o.* FROM outbox x JOIN"
                                    + " payment_orders o ON o.id=x.aggregate_id WHERE x.id=?",
                            c.get("id")));
        for (var j : jobs)
            try {
                UUID event = (UUID) j.get("event_id"), order = (UUID) j.get("id");
                org.slf4j.MDC.put("correlationId", j.get("correlation_id").toString());
                UUID account = (UUID) j.get("account_id");
                if (account == null) {
                    var result =
                            iam.post()
                                    .uri("/internal/v1/accounts/purchase-confirmations")
                                    .header("Idempotency-Key", event.toString())
                                    .body(
                                            Map.of(
                                                    "eventId",
                                                    event,
                                                    "purchaseOrderId",
                                                    order,
                                                    "email",
                                                    j.get("email"),
                                                    "firstName",
                                                    j.get("first_name"),
                                                    "lastName",
                                                    j.get("last_name"),
                                                    "confirmed",
                                                    true))
                                    .retrieve()
                                    .body(Map.class);
                    account = UUID.fromString(result.get("accountId").toString());
                    db.update(
                            "UPDATE payment_orders SET account_id=? WHERE id=? AND account_id IS"
                                    + " NULL",
                            account,
                            order);
                }
                installation
                        .post()
                        .uri("/internal/v1/installations/payment-confirmations")
                        .header("Idempotency-Key", event.toString())
                        .body(
                                Map.of(
                                        "eventId",
                                        event,
                                        "purchaseOrderId",
                                        order,
                                        "installationId",
                                        j.get("installation_id"),
                                        "customerAccountId",
                                        account,
                                        "confirmed",
                                        true))
                        .retrieve()
                        .toBodilessEntity();
                db.update(
                        "UPDATE outbox SET"
                                + " delivered_at=now(),installation_delivered=true,lease_until=NULL"
                                + " WHERE id=?",
                        event);
            } catch (Exception e) {
                db.update(
                        "UPDATE outbox SET attempts=attempts+1,available_at=now()+interval '30"
                                + " seconds',lease_until=NULL WHERE id=?",
                        j.get("event_id"));
            } finally {
                org.slf4j.MDC.remove("correlationId");
            }
    }
}
