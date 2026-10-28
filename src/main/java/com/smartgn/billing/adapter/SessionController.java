package com.smartgn.billing.adapter;

import com.smartgn.billing.application.*;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@Profile("local")
public class SessionController {
    private final BillingService service;

    public SessionController(BillingService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/checkout/orders/{id}/sessions")
    public Object session(
            @PathVariable UUID id, @RequestHeader("X-Checkout-Secret") String secret) {
        service.safeOrder(id, secret, null);
        return Map.of(
                "orderId",
                id,
                "testMode",
                true,
                "provider",
                "LOCAL_TEST_DOUBLE",
                "instructions",
                "Register a controlled result through the authenticated internal fixture then"
                        + " submit its provider reference for verification");
    }
}
