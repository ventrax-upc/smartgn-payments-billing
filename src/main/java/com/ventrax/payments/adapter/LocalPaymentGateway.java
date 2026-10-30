package com.ventrax.payments.adapter;

import com.ventrax.payments.application.*;
import com.ventrax.payments.domain.BillingPolicy;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Component
@Profile("local")
public class LocalPaymentGateway implements PaymentGateway {
    private final JdbcTemplate db;

    public LocalPaymentGateway(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void fixture(String ref, UUID order, String status, BigDecimal amount) {
        if (ref == null
                || ref.length() > 200
                || ref.isBlank()
                || !Set.of("CONFIRMED", "DECLINED", "UNKNOWN").contains(status))
            throw BillingService.error(HttpStatus.BAD_REQUEST, "Invalid provider fixture");
        BillingPolicy.money(amount);
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "provider:" + ref);
        var old = db.queryForList("SELECT * FROM local_provider_results WHERE reference=?", ref);
        if (!old.isEmpty()) {
            var r = old.getFirst();
            if (!order.equals(r.get("order_id"))
                    || amount.compareTo((BigDecimal) r.get("amount")) != 0
                    || (!"UNKNOWN".equals(r.get("status")) && !status.equals(r.get("status"))))
                throw BillingService.error(HttpStatus.CONFLICT, "Provider fixture conflict");
        }
        db.update(
                "INSERT INTO local_provider_results(reference,order_id,status,amount,confirmed_at)"
                    + " VALUES (?,?,?,?,CASE WHEN ?='CONFIRMED' THEN now() END) ON"
                    + " CONFLICT(reference) DO UPDATE SET"
                    + " status=excluded.status,confirmed_at=COALESCE(local_provider_results.confirmed_at,excluded.confirmed_at)",
                ref,
                order,
                status,
                amount,
                status);
    }

    public Result verify(String ref, UUID order, BigDecimal amount) {
        var row =
                db
                        .queryForList(
                                "SELECT * FROM local_provider_results WHERE reference=? AND"
                                        + " order_id=?",
                                ref,
                                order)
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        BillingService.error(
                                                HttpStatus.CONFLICT, "Unverified provider result"));
        if (amount.compareTo((BigDecimal) row.get("amount")) != 0)
            throw BillingService.error(HttpStatus.CONFLICT, "Provider amount mismatch");
        return new Result(
                ref,
                row.get("status").toString(),
                amount,
                true,
                row.get("confirmed_at") == null
                        ? null
                        : ((java.sql.Timestamp) row.get("confirmed_at")).toInstant());
    }

    public CardProof card(String token, UUID account) {
        var r =
                db
                        .queryForList(
                                "SELECT * FROM local_provider_cards WHERE token_hash=? AND"
                                        + " account_id=?",
                                BillingService.hash(token),
                                account)
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        BillingService.error(
                                                HttpStatus.FORBIDDEN,
                                                "Provider token ownership not verified"));
        return new CardProof(
                account,
                r.get("brand").toString(),
                r.get("last_four").toString(),
                (boolean) r.get("recurring_authorized"),
                true);
    }

    @Transactional
    public Result charge(
            UUID operation, UUID order, BigDecimal amount, String token, boolean recurring) {
        String ref = "local-" + operation;
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "provider:" + ref);
        if (!db.queryForList("SELECT reference FROM local_provider_results WHERE reference=?", ref)
                .isEmpty()) return verify(ref, order, amount);
        var c =
                db.queryForMap(
                        "SELECT * FROM local_provider_cards WHERE token_hash=?",
                        BillingService.hash(token));
        if (recurring && !(boolean) c.get("recurring_authorized"))
            throw BillingService.error(HttpStatus.CONFLICT, "Recurring consent missing");
        fixture(ref, order, c.get("next_status").toString(), amount);
        return verify(ref, order, amount);
    }
}
