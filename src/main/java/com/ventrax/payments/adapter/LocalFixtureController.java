package com.ventrax.payments.adapter;

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

    public LocalFixtureController(
            LocalPaymentGateway gateway, org.springframework.jdbc.core.JdbcTemplate db) {
        this.db = db;
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

    private final org.springframework.jdbc.core.JdbcTemplate db;

    public record CardFixture(
            @NotNull UUID accountId,
            @NotBlank @Pattern(regexp = "test_[a-zA-Z0-9_-]{16,160}") String providerToken,
            @NotBlank @Size(max = 30) String brand,
            @Pattern(regexp = "[0-9]{4}") @NotNull String lastFour,
            boolean recurringAuthorized,
            @NotBlank @Pattern(regexp = "CONFIRMED|DECLINED|UNKNOWN") String nextStatus) {}

    @PostMapping("/internal/v1/local-provider/cards")
    public void card(@Valid @RequestBody CardFixture r) {
        int updated =
                db.update(
                        "INSERT INTO local_provider_cards VALUES (?,?,?,?,?,?) ON"
                            + " CONFLICT(token_hash) DO UPDATE SET next_status=excluded.next_status"
                            + " WHERE local_provider_cards.account_id=excluded.account_id AND"
                            + " local_provider_cards.brand=excluded.brand AND"
                            + " local_provider_cards.last_four=excluded.last_four AND"
                            + " local_provider_cards.recurring_authorized=excluded.recurring_authorized",
                        com.ventrax.payments.application.BillingService.hash(r.providerToken()),
                        r.accountId(),
                        r.brand(),
                        r.lastFour(),
                        r.recurringAuthorized(),
                        r.nextStatus());
        if (updated == 0)
            throw com.ventrax.payments.application.BillingService.error(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Provider token identity conflicts with existing evidence");
    }
}
