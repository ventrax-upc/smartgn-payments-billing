package com.smartgn.billing.adapter;

import com.smartgn.billing.application.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController
public class BillingController {
    private final BillingService service;

    private final BigDecimal technicalBase;

    public BillingController(
            BillingService service,
            @org.springframework.beans.factory.annotation.Value("${smartgn.technical-base-price}")
                    BigDecimal technicalBase) {
        this.service = service;
        this.technicalBase = com.smartgn.billing.domain.BillingPolicy.money(technicalBase);
    }

    public record Checkout(
            @NotNull UUID installationId,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName) {}

    public record Pro(@NotNull UUID paymentMethodId, boolean autoRenew) {}

    public record Confirmation(@NotNull UUID orderId, @NotBlank String providerReference) {}

    public record Visit(
            @NotNull UUID eventId,
            @NotNull UUID visitId,
            @NotNull UUID accountId,
            @NotNull UUID meterId,
            @NotNull Instant completedAt,
            @NotNull @DecimalMin("0.00") BigDecimal cost) {}

    @PostMapping("/api/v1/checkout/orders")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object checkout(
            @Valid @RequestBody Checkout r,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 200) String key,
            @RequestHeader("X-Checkout-Secret") @Size(min = 32, max = 200) String secret) {
        return service.checkout(
                r.installationId(), r.email(), r.firstName(), r.lastName(), key, secret);
    }

    @GetMapping("/api/v1/checkout/orders/{id}")
    public Object order(@PathVariable UUID id, @RequestHeader("X-Checkout-Secret") String secret) {
        return service.safeOrder(id, secret, null);
    }

    @PostMapping("/api/v1/subscriptions/pro")
    public Object pro(
            @RequestAttribute("actor") IdentityPort.Account actor,
            @Valid @RequestBody Pro r,
            @RequestHeader("Idempotency-Key") String key) {
        return service.subscribe(actor, r.paymentMethodId(), r.autoRenew(), key);
    }

    @GetMapping("/api/v1/subscriptions/me")
    public Object plan(@RequestAttribute("actor") IdentityPort.Account actor) {
        var result = service.entitlement(service.principal(actor));
        result.put("technicalBasePriceReference", technicalBase);
        return result;
    }

    @GetMapping("/internal/v1/accounts/{id}/entitlements")
    public Object entitlement(@PathVariable UUID id) {
        return service.entitlement(id);
    }

    @PostMapping("/internal/v1/payments/confirmations")
    public Object confirmation(@Valid @RequestBody Confirmation r) {
        return service.confirm(r.orderId(), r.providerReference());
    }

    @PostMapping("/internal/v1/technical-visits/completions")
    public void visit(@Valid @RequestBody Visit r) {
        service.visit(
                r.eventId(), r.visitId(), r.accountId(), r.meterId(), r.completedAt(), r.cost());
    }

    @GetMapping("/api/v1/payments/history")
    public Object history(
            @RequestAttribute("actor") IdentityPort.Account actor,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String status) {
        return service.history(actor, offset, limit, reason, status);
    }

    @GetMapping("/api/v1/billing-documents/{id}")
    public Object document(
            @RequestAttribute("actor") IdentityPort.Account actor, @PathVariable UUID id) {
        return service.document(actor, id);
    }
}
