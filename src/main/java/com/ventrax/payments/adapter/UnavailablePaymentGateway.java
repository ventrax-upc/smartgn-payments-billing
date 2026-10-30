package com.ventrax.payments.adapter;

import com.ventrax.payments.application.PaymentGateway;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Profile("!local")
public class UnavailablePaymentGateway implements PaymentGateway {
    public Result verify(String ref, UUID order, BigDecimal amount) {
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Niubiz merchant integration is not configured");
    }

    public CardProof card(String token, UUID account) {
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Niubiz tokenization is not configured");
    }

    public Result charge(
            UUID operation, UUID order, BigDecimal amount, String token, boolean recurring) {
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Niubiz merchant debit integration is not configured");
    }
}
