package com.smartgn.billing.adapter;

import com.smartgn.billing.application.PaymentGateway;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

@Component
@Profile("local")
public class LocalPaymentGateway implements PaymentGateway {
    private final Map<String, Result> results = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, UUID> orders = new java.util.concurrent.ConcurrentHashMap<>();

    public synchronized void fixture(String ref, UUID order, String status, BigDecimal amount) {
        if (!Set.of("CONFIRMED", "DECLINED", "UNKNOWN").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (orders.containsKey(ref) && !orders.get(ref).equals(order))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        var old = results.get(ref);
        if (old != null
                && !old.status().equals("UNKNOWN")
                && !old.equals(new Result(ref, status, amount, true)))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        orders.put(ref, order);
        results.put(ref, new Result(ref, status, amount, true));
    }

    public Result verify(String ref, UUID order, BigDecimal amount) {
        var result = results.get(ref);
        if (result == null
                || !order.equals(orders.get(ref))
                || amount.compareTo(result.amount()) != 0)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Unverified local provider result");
        return result;
    }
}
