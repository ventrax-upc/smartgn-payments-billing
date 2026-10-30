package com.ventrax.payments.adapter;

import com.ventrax.payments.application.*;

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

    public record Pay(@jakarta.validation.constraints.NotNull UUID paymentMethodId) {}

    @org.springframework.beans.factory.annotation.Autowired
    private com.ventrax.payments.application.PaymentOperations operations;

    @PostMapping("/api/v1/payments/orders/{id}/pay")
    public Object pay(
            @RequestAttribute("actor") IdentityPort.Account actor,
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody Pay request) {
        return operations.pay(actor, id, request.paymentMethodId());
    }

    @PatchMapping("/api/v1/subscriptions/me/renewal")
    public void renewal(
            @RequestAttribute("actor") IdentityPort.Account actor, @RequestBody Renewal r) {
        UUID account = billing.principal(actor);
        if (r.autoRenew()
                && db.queryForList(
                                "SELECT s.account_id FROM subscriptions s JOIN payment_methods m ON"
                                        + " m.id=s.method_id WHERE s.account_id=? AND m.active AND"
                                        + " m.recurring_authorized",
                                account)
                        .isEmpty())
            throw BillingService.error(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Authorized recurring method required");
        com.ventrax.payments.configuration.Trace.audit(db, account, "AUTO_RENEW_CHANGED", account);
        if (db.update(
                        "UPDATE subscriptions SET auto_renew=? WHERE account_id=?",
                        r.autoRenew(),
                        billing.principal(actor))
                == 0)
            throw BillingService.error(
                    org.springframework.http.HttpStatus.CONFLICT, "Subscription not initialized");
    }

    @PostMapping("/api/v1/payments/orders/{id}/retry")
    public Object retry(
            @RequestAttribute("actor") IdentityPort.Account actor,
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody Pay request,
            @RequestHeader("Idempotency-Key") String key) {
        return operations.retry(actor, id, request.paymentMethodId(), key);
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
