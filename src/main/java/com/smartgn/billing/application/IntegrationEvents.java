package com.smartgn.billing.application;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class IntegrationEvents {
    private final BillingService billing;
    private final JdbcTemplate db;
    private final tools.jackson.databind.ObjectMapper json =
            new tools.jackson.databind.ObjectMapper();

    public IntegrationEvents(BillingService billing, JdbcTemplate db) {
        this.billing = billing;
        this.db = db;
    }

    public void receive(Map<String, Object> event) {
        String old = org.slf4j.MDC.get("correlationId");
        try {
            if (event.get("correlation_id") != null)
                org.slf4j.MDC.put(
                        "correlationId",
                        UUID.fromString(event.get("correlation_id").toString()).toString());
            accept(event);
        } finally {
            if (old == null) org.slf4j.MDC.remove("correlationId");
            else org.slf4j.MDC.put("correlationId", old);
        }
    }

    private void accept(Map<String, Object> event) {
        UUID id = UUID.fromString(required(event, "event_id")),
                aggregate = UUID.fromString(required(event, "aggregate_id"));
        String type = required(event, "event_type");
        Object raw = event.get("payload");
        Map payload;
        if (raw instanceof String s) payload = json.readValue(s, Map.class);
        else if (raw instanceof Map m) payload = m;
        else throw new IllegalArgumentException("Event payload must be an object");
        if (payload == null) throw new IllegalArgumentException("Payload required");
        switch (type) {
            case "InstallationCompleted" -> {
                UUID account = UUID.fromString(required(payload, "accountId"));
                if (db.queryForList(
                                "SELECT id FROM payment_orders WHERE installation_id=? AND"
                                        + " account_id=? AND status='CONFIRMED'",
                                aggregate,
                                account)
                        .isEmpty())
                    throw BillingService.error(
                            HttpStatus.CONFLICT,
                            "Completed installation does not match a paid purchase");
                billing.installed(
                        id,
                        account,
                        Instant.parse(required(payload, "completedAt")),
                        BillingService.hash(
                                type
                                        + "|"
                                        + aggregate
                                        + "|"
                                        + json.writeValueAsString(new TreeMap<>(payload))));
            }
            case "TechnicalVisitCompleted" -> {
                UUID visit = UUID.fromString(required(payload, "visitId"));
                if (!aggregate.equals(visit))
                    throw new IllegalArgumentException("Visit aggregate mismatch");
                billing.visit(
                        id,
                        visit,
                        UUID.fromString(required(payload, "accountId")),
                        UUID.fromString(required(payload, "meterId")),
                        Instant.parse(required(payload, "completedAt")),
                        new BigDecimal(required(payload, "cost")));
            }
            default -> {
                if (!Set.of(
                                "InstallationRequested",
                                "InstallerAssigned",
                                "InstallationAppointmentChanged",
                                "InstallationPaymentVerified",
                                "DeviceRegistered",
                                "AccountProvisioned",
                                "AccountActivated",
                                "CredentialsChanged",
                                "MeterPermissionsChanged")
                        .contains(type))
                    throw new IllegalArgumentException("Unsupported event type");
            }
        }
    }

    private static String required(Map m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank())
            throw new IllegalArgumentException("Missing event field");
        return v.toString();
    }
}
