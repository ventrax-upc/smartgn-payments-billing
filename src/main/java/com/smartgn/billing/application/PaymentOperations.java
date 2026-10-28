package com.smartgn.billing.application;

import com.smartgn.billing.adapter.TokenProtector;
import com.smartgn.billing.configuration.Trace;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PaymentOperations {
    private final JdbcTemplate db;
    private final BillingService billing;
    private final TokenProtector tokens;

    public PaymentOperations(JdbcTemplate db, BillingService billing, TokenProtector tokens) {
        this.db = db;
        this.billing = billing;
        this.tokens = tokens;
    }

    @Transactional
    public Object pay(IdentityPort.Account actor, UUID order, UUID method) {
        UUID a = billing.principal(actor);
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", a.toString());
        var o = billing.order(order);
        if (!a.equals(o.get("account_id")))
            throw BillingService.error(HttpStatus.FORBIDDEN, "Order is not owned");
        if ("CONFIRMED".equals(o.get("status"))) return billing.safeOrder(order, null, actor);
        if (db.queryForList(
                        "SELECT id FROM payment_methods WHERE id=? AND account_id=? AND active",
                        method,
                        a)
                .isEmpty()) throw BillingService.error(HttpStatus.FORBIDDEN, "Card is not owned");
        var existing = db.queryForList("SELECT * FROM payment_jobs WHERE order_id=?", order);
        if (!existing.isEmpty()) {
            var j = existing.getFirst();
            if (!method.equals(j.get("method_id")))
                throw BillingService.error(
                        HttpStatus.CONFLICT, "Payment already assigned to another method");
            if ("DECLINED".equals(j.get("status")))
                throw BillingService.error(
                        HttpStatus.CONFLICT, "Declined operation; use the explicit retry endpoint");
            return Map.of("operationId", j.get("id"), "status", j.get("status"));
        }
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO payment_jobs(id,order_id,method_id) VALUES (?,?,?)",
                id,
                order,
                method);
        db.update("UPDATE payment_orders SET method_id=? WHERE id=?", method, order);
        Trace.audit(db, a, "PAYMENT_REQUESTED", order);
        return Map.of("operationId", id, "status", "PENDING");
    }

    @Transactional
    public Object retry(IdentityPort.Account actor, UUID order, UUID method, String key) {
        UUID a = billing.principal(actor);
        if (key == null || key.isBlank() || key.length() > 200)
            throw BillingService.error(HttpStatus.BAD_REQUEST, "Idempotency key required");
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", a.toString());
        var o = billing.order(order);
        if (!a.equals(o.get("account_id")))
            throw BillingService.error(HttpStatus.FORBIDDEN, "Order is not owned");
        var jobs = db.queryForList("SELECT * FROM payment_jobs WHERE order_id=?", order);
        UUID operation =
                UUID.nameUUIDFromBytes(
                        (order + ":" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!jobs.isEmpty() && operation.equals(jobs.getFirst().get("id"))) {
            if (!method.equals(jobs.getFirst().get("method_id")))
                throw BillingService.error(HttpStatus.CONFLICT, "Retry key conflicts with method");
            return Map.of("operationId", operation, "status", jobs.getFirst().get("status"));
        }
        if (!"DECLINED".equals(o.get("status"))
                || jobs.isEmpty()
                || !"DECLINED".equals(jobs.getFirst().get("status")))
            throw BillingService.error(
                    HttpStatus.CONFLICT, "Only a verified final decline can be retried");
        if (db.queryForList(
                        "SELECT id FROM payment_methods WHERE id=? AND account_id=? AND active",
                        method,
                        a)
                .isEmpty()) throw BillingService.error(HttpStatus.FORBIDDEN, "Card is not owned");
        db.update(
                "UPDATE payment_jobs SET"
                    + " id=?,method_id=?,status='PENDING',provider_reference=NULL,available_at=now(),lease_until=NULL"
                    + " WHERE order_id=?",
                operation,
                method,
                order);
        db.update(
                "UPDATE payment_orders SET status='PENDING',method_id=? WHERE id=?", method, order);
        Trace.audit(db, a, "PAYMENT_RETRY_REQUESTED", order);
        return Map.of("operationId", operation, "status", "PENDING");
    }

    public String token(UUID method) {
        return tokens.reveal(
                db.queryForObject(
                        "SELECT protected_token FROM payment_methods WHERE id=?",
                        String.class,
                        method));
    }
}
