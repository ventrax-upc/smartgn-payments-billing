package com.smartgn.billing.adapter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@Profile("local")
public class LocalFixtureController {
    private final LocalPaymentGateway gateway;

    public LocalFixtureController(LocalPaymentGateway gateway) {
        this.gateway = gateway;
    }

    public record Fixture(
            @NotBlank String reference,
            @NotNull UUID orderId,
            @NotBlank String status,
            @NotNull @DecimalMin("0.00") BigDecimal amount) {}

    @PostMapping("/internal/v1/local-provider/results")
    public void result(@Valid @RequestBody Fixture f) {
        gateway.fixture(f.reference(), f.orderId(), f.status(), f.amount());
    }
}
