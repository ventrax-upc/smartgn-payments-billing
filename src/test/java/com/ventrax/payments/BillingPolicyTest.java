package com.ventrax.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.ventrax.payments.domain.BillingPolicy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

class BillingPolicyTest {
    @Test
    void endOfMonthUsesCalendar() {
        assertEquals(
                Instant.parse("2026-02-28T15:00:00Z"),
                BillingPolicy.monthEnd(Instant.parse("2026-01-31T15:00:00Z")));
    }

    @Test
    void paidEndIsExclusive() {
        var a = Instant.parse("2026-01-01T00:00:00Z");
        var b = BillingPolicy.monthEnd(a);
        assertTrue(BillingPolicy.covers(a, b, a));
        assertFalse(BillingPolicy.covers(a, b, b));
    }

    @Test
    void rejectsNegativeMoney() {
        assertThrows(
                IllegalArgumentException.class, () -> BillingPolicy.money(new BigDecimal("-1")));
    }

    @Test
    void rejectsFractionalCent() {
        assertThrows(
                IllegalArgumentException.class, () -> BillingPolicy.money(new BigDecimal("1.001")));
    }
}
