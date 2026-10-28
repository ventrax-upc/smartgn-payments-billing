package com.smartgn.billing.adapter;

import com.smartgn.billing.application.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class PaymentJobWorker {
    private final JdbcTemplate db;
    private final PaymentGateway gateway;
    private final BillingService billing;
    private final PaymentOperations operations;

    public PaymentJobWorker(
            JdbcTemplate db,
            PaymentGateway gateway,
            BillingService billing,
            PaymentOperations operations) {
        this.db = db;
        this.gateway = gateway;
        this.billing = billing;
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${smartgn.worker-delay:1000}")
    public void run() {
        UUID claim = UUID.randomUUID();
        var jobs =
                db.queryForList(
                        "UPDATE payment_jobs SET lease_id=?,lease_until=now()+interval '30 seconds'"
                            + " WHERE id=(SELECT id FROM payment_jobs WHERE status IN"
                            + " ('PENDING','UNKNOWN') AND available_at<=now() AND (lease_until IS"
                            + " NULL OR lease_until<now()) ORDER BY available_at LIMIT 1 FOR UPDATE"
                            + " SKIP LOCKED) RETURNING *",
                        claim);
        for (var j : jobs)
            try {
                var o = billing.order((UUID) j.get("order_id"));
                var result =
                        gateway.charge(
                                (UUID) j.get("id"),
                                (UUID) o.get("id"),
                                (BigDecimal) o.get("amount"),
                                operations.token((UUID) j.get("method_id")),
                                o.get("requested_period_start") != null);
                billing.confirm((UUID) o.get("id"), result.reference());
                db.update(
                        "UPDATE payment_jobs SET"
                                + " status=?,provider_reference=?,available_at=now()+interval '15"
                                + " seconds',lease_until=NULL WHERE id=? AND lease_id=?",
                        result.status(),
                        result.reference(),
                        j.get("id"),
                        claim);
            } catch (Exception e) {
                db.update(
                        "UPDATE payment_jobs SET attempts=attempts+1,available_at=now()+interval"
                                + " '15 seconds',lease_until=NULL WHERE id=? AND lease_id=?",
                        j.get("id"),
                        claim);
            }
    }
}
