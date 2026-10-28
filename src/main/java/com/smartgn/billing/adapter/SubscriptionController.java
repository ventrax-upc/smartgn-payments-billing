package com.smartgn.billing.adapter;

import com.smartgn.billing.application.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class SubscriptionController {
    private final BillingService billing;
    private final JdbcTemplate db;

    public SubscriptionController(BillingService billing, JdbcTemplate db) {
        this.billing = billing;
        this.db = db;
    }

    public record Renewal(boolean autoRenew) {}

    @PatchMapping("/api/v1/subscriptions/me/renewal")
    public void renewal(
            @RequestAttribute("actor") IdentityPort.Account actor, @RequestBody Renewal r) {
        if (db.update(
                        "UPDATE subscriptions SET auto_renew=? WHERE account_id=?",
                        r.autoRenew(),
                        billing.principal(actor))
                == 0)
            throw BillingService.error(
                    org.springframework.http.HttpStatus.CONFLICT, "Subscription not initialized");
    }

    @GetMapping("/api/v1/technical-statements/{id}")
    public Object statement(
            @RequestAttribute("actor") IdentityPort.Account actor, @PathVariable UUID id) {
        return db
                .queryForList(
                        "SELECT * FROM coverage_decisions WHERE visit_id=? AND account_id=?",
                        id,
                        billing.principal(actor))
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                BillingService.error(
                                        org.springframework.http.HttpStatus.NOT_FOUND,
                                        "Statement not found"));
    }

    @GetMapping("/api/v1/payments/orders/{id}")
    public Object order(
            @RequestAttribute("actor") IdentityPort.Account actor, @PathVariable UUID id) {
        return billing.safeOrder(id, null, actor);
    }
}
