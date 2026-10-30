package com.ventrax.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface PaymentGateway {
    record Result(
            String reference,
            String status,
            BigDecimal amount,
            boolean testMode,
            Instant confirmedAt) {
        public Result(String reference, String status, BigDecimal amount, boolean testMode) {
            this(reference, status, amount, testMode, Instant.now());
        }
    }

    record CardProof(
            UUID accountId,
            String brand,
            String lastFour,
            boolean recurringAuthorized,
            boolean testMode) {}

    Result verify(String reference, UUID orderId, BigDecimal amount);

    default CardProof card(String token, UUID account) {
        throw new IllegalStateException("Provider tokenization unavailable");
    }

    default Result charge(
            UUID operation, UUID order, BigDecimal amount, String token, boolean recurring) {
        throw new IllegalStateException("Provider charge unavailable");
    }
}
