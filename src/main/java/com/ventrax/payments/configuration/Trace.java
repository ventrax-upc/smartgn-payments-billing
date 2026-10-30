package com.ventrax.payments.configuration;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

public final class Trace {
    private Trace() {}

    public static UUID correlation() {
        String id = MDC.get("correlationId");
        return id == null ? UUID.randomUUID() : UUID.fromString(id);
    }

    public static void audit(JdbcTemplate db, UUID actor, String action, UUID resource) {
        audit(db, actor, action, resource, "{}");
    }

    public static void audit(
            JdbcTemplate db, UUID actor, String action, UUID resource, String details) {
        db.update(
                "INSERT INTO"
                    + " billing_audit(id,actor_id,action,resource_id,occurred_at,correlation_id,details)"
                    + " VALUES (?,?,?,?,now(),?,?)",
                UUID.randomUUID(),
                actor,
                action,
                resource,
                correlation(),
                details);
    }
}
