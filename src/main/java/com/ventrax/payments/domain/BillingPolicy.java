package com.ventrax.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

public final class BillingPolicy {
    private BillingPolicy() {}

    public static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 2)
            throw new IllegalArgumentException("Invalid monetary amount");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static Instant monthEnd(Instant start) {
        return start.atZone(ZoneId.of("America/Lima")).plusMonths(1).toInstant();
    }

    public static boolean covers(Instant start, Instant end, Instant at) {
        return !at.isBefore(start) && at.isBefore(end);
    }
}
