package com.ventrax.payments.adapter;

import com.ventrax.payments.application.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@Profile("local")
public class SessionController {
    private static final String LOCAL_CHECKOUT_ACTION = "SMARTGN_LOCAL_CHECKOUT";
    private final BillingService service;

    private final NiubizSandbox sandbox;
    private final LocalPaymentGateway localGateway;
    private final boolean browserSimulationEnabled;

    public SessionController(
            BillingService service, NiubizSandbox sandbox, LocalPaymentGateway localGateway,
            @Value("${LOCAL_BROWSER_CHECKOUT_ENABLED:false}") boolean browserSimulationEnabled) {
        this.service = service;
        this.sandbox = sandbox;
        this.localGateway = localGateway;
        this.browserSimulationEnabled = browserSimulationEnabled;
    }

    @PostMapping("/api/v1/checkout/orders/{id}/sessions")
    public Object session(
            @PathVariable UUID id, @RequestHeader("X-Checkout-Secret") String secret) {
        var order = service.safeOrder(id, secret, null);
        if (sandbox.enabled()) return sandbox.session(id, order);
        return Map.of(
                "orderId",
                id,
                "testMode",
                true,
                "provider",
                "LOCAL_TEST_DOUBLE",
                "localCheckoutAction", browserSimulationEnabled ? LOCAL_CHECKOUT_ACTION : "");
    }

    public record Authorization(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{16,200}") String transactionToken) {}

    @PostMapping("/api/v1/checkout/orders/{id}/sandbox-authorizations")
    public Object authorize(@PathVariable UUID id, @RequestHeader("X-Checkout-Secret") String secret,
            @jakarta.validation.Valid @RequestBody Authorization body) {
        var order = service.safeOrder(id, secret, null);
        if (sandbox.enabled() || !browserSimulationEnabled || !LOCAL_CHECKOUT_ACTION.equals(body.transactionToken())) {
            return sandbox.authorize(id, order, body.transactionToken(), service);
        }
        if (!"INITIAL".equals(order.get("reason"))) {
            throw BillingService.error(HttpStatus.CONFLICT, "Only initial purchases use the local checkout");
        }
        if ("CONFIRMED".equals(order.get("status"))) {
            return order;
        }
        if (!"PENDING".equals(order.get("status"))) {
            throw BillingService.error(HttpStatus.CONFLICT, "The local checkout order is not pending");
        }
        String reference = "local-browser-" + id;
        localGateway.fixture(reference, id, "CONFIRMED", (BigDecimal) order.get("amount"));
        return service.confirm(id, reference);
    }
}
