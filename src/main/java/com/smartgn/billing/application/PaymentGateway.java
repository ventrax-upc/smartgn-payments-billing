package com.smartgn.billing.application;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {
    record Result(String reference, String status, BigDecimal amount, boolean testMode) {}

    Result verify(String reference, UUID orderId, BigDecimal amount);
}
