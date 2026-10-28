package com.smartgn.billing.adapter;

import com.smartgn.billing.application.BillingService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Component
public class RenewalWorker {
    private final JdbcTemplate db;
    private final BigDecimal price;

    public RenewalWorker(JdbcTemplate db, @Value("${smartgn.pro-price}") BigDecimal price) {
        this.db = db;
        this.price = com.smartgn.billing.domain.BillingPolicy.money(price);
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void due() {
        var rows =
                db.queryForList(
                        "SELECT s.account_id,max(p.ends_at) AS due FROM subscriptions s JOIN"
                            + " subscription_periods p USING(account_id) JOIN payment_methods m ON"
                            + " m.id=s.method_id AND m.active WHERE s.auto_renew GROUP BY"
                            + " s.account_id HAVING max(p.ends_at)<=now()");
        for (var row : rows) {
            UUID a = (UUID) row.get("account_id");
            db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", a.toString());
            String key = "renewal:" + a + ":" + row.get("due");
            if (!db.queryForList(
                            "SELECT id FROM payment_orders WHERE idempotency_key=? OR (account_id=?"
                                + " AND reason='PRO' AND status IN ('PENDING','UNKNOWN'))",
                            key,
                            a)
                    .isEmpty()) continue;
            UUID id = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " payment_orders(id,account_id,reason,amount,idempotency_key,fingerprint)"
                        + " VALUES (?,?,'PRO',?,?,?)",
                    id,
                    a,
                    price,
                    key,
                    BillingService.hash(key));
            db.update(
                    "INSERT INTO order_lines VALUES (?,?,'PRO_MONTHLY',?)",
                    UUID.randomUUID(),
                    id,
                    price);
        }
    }
}
