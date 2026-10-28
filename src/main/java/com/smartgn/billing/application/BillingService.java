package com.smartgn.billing.application;

import com.smartgn.billing.domain.BillingPolicy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class BillingService {
    private final JdbcTemplate db;
    private final PaymentGateway gateway;
    private final RestClient installation;
    private final BigDecimal device, installationPrice, pro;

    public BillingService(
            JdbcTemplate db,
            PaymentGateway gateway,
            @Value("${smartgn.installation-url}") String url,
            @Value("${smartgn.internal-token}") String token,
            @Value("${smartgn.device-price}") BigDecimal device,
            @Value("${smartgn.installation-price}") BigDecimal installationPrice,
            @Value("${smartgn.pro-price}") BigDecimal pro) {
        this.db = db;
        this.gateway = gateway;
        this.device = BillingPolicy.money(device);
        this.installationPrice = BillingPolicy.money(installationPrice);
        this.pro = BillingPolicy.money(pro);
        var f =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(2))
                                .build());
        f.setReadTimeout(Duration.ofSeconds(3));
        installation =
                RestClient.builder()
                        .baseUrl(url)
                        .requestFactory(f)
                        .defaultHeader("Authorization", "Bearer " + token)
                        .build();
    }

    public static String hash(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void lock(String key) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", key);
    }

    public static ResponseStatusException error(HttpStatus code, String message) {
        return new ResponseStatusException(code, message);
    }

    public UUID principal(IdentityPort.Account a) {
        if (a == null
                || !"USER".equals(a.role())
                || !"PRINCIPAL".equals(a.accountKind())
                || !"ACTIVE".equals(a.status()))
            throw error(HttpStatus.FORBIDDEN, "Principal required");
        return a.accountId();
    }

    public Map<String, Object> order(UUID id) {
        return db.queryForList("SELECT * FROM payment_orders WHERE id=?", id).stream()
                .findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Order not found"));
    }

    public Map<String, Object> safeOrder(UUID id, String secret, IdentityPort.Account actor) {
        var o = order(id);
        if (secret != null && Objects.equals(o.get("checkout_hash"), hash(secret)))
            return publicOrder(o);
        if (actor != null && principal(actor).equals(o.get("account_id"))) return publicOrder(o);
        throw error(HttpStatus.FORBIDDEN, "Order access denied");
    }

    private Map<String, Object> publicOrder(Map<String, Object> o) {
        var m = new LinkedHashMap<String, Object>();
        for (String k :
                List.of(
                        "id",
                        "reason",
                        "amount",
                        "currency",
                        "status",
                        "created_at",
                        "confirmed_at")) m.put(k, o.get(k));
        return m;
    }

    @Transactional
    public Map<String, Object> checkout(
            UUID request, String email, String first, String last, String key, String secret) {
        if (key == null
                || key.isBlank()
                || key.length() > 200
                || secret == null
                || secret.length() < 32
                || secret.length() > 200)
            throw error(HttpStatus.BAD_REQUEST, "Invalid idempotency key or checkout secret");
        lock("checkout:" + request);
        String fp =
                hash(
                        request
                                + "|"
                                + email.toLowerCase(Locale.ROOT)
                                + "|"
                                + first
                                + "|"
                                + last
                                + "|"
                                + hash(secret));
        var old = db.queryForList("SELECT * FROM payment_orders WHERE idempotency_key=?", key);
        if (!old.isEmpty()) {
            if (!fp.equals(old.getFirst().get("fingerprint")))
                throw error(HttpStatus.CONFLICT, "Idempotency conflict");
            return publicOrder(old.getFirst());
        }
        Map context;
        try {
            context =
                    installation
                            .get()
                            .uri("/internal/v1/installations/{id}/checkout-context", request)
                            .retrieve()
                            .body(Map.class);
        } catch (Exception e) {
            throw error(
                    HttpStatus.SERVICE_UNAVAILABLE, "Installation checkout context unavailable");
        }
        if (context == null
                || !"PROVISIONAL".equals(context.get("status"))
                || !email.equalsIgnoreCase(context.get("email").toString()))
            throw error(HttpStatus.CONFLICT, "Request does not match checkout");
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " payment_orders(id,installation_id,reason,amount,email,first_name,last_name,checkout_hash,idempotency_key,fingerprint)"
                    + " VALUES (?,?,'INITIAL',?,?,?,?,?,?,?)",
                id,
                request,
                device.add(installationPrice),
                email.toLowerCase(Locale.ROOT),
                first,
                last,
                hash(secret),
                key,
                fp);
        line(id, "DEVICE", device);
        line(id, "INSTALLATION", installationPrice);
        return publicOrder(order(id));
    }

    private void line(UUID order, String concept, BigDecimal amount) {
        db.update(
                "INSERT INTO order_lines VALUES (?,?,?,?)",
                UUID.randomUUID(),
                order,
                concept,
                amount);
    }

    @Transactional
    public Map<String, Object> subscribe(
            IdentityPort.Account actor, UUID method, boolean auto, String key) {
        UUID account = principal(actor);
        lock(account.toString());
        if (db.queryForList("SELECT account_id FROM subscriptions WHERE account_id=?", account)
                .isEmpty()) throw error(HttpStatus.CONFLICT, "Completed installation required");
        if (db.queryForList(
                        "SELECT id FROM payment_methods WHERE id=? AND account_id=? AND active",
                        method,
                        account)
                .isEmpty()) throw error(HttpStatus.FORBIDDEN, "Payment method unavailable");
        String fp = hash(account + "|" + method + "|" + auto);
        var old = db.queryForList("SELECT * FROM payment_orders WHERE idempotency_key=?", key);
        if (!old.isEmpty()) {
            if (!fp.equals(old.getFirst().get("fingerprint")))
                throw error(HttpStatus.CONFLICT, "Idempotency conflict");
            return publicOrder(old.getFirst());
        }
        if (Boolean.TRUE.equals(entitlement(account).get("proActive")))
            throw error(HttpStatus.CONFLICT, "Pro is already active");
        if (!db.queryForList(
                        "SELECT id FROM payment_orders WHERE account_id=? AND reason='PRO' AND"
                            + " status IN ('PENDING','UNKNOWN')",
                        account)
                .isEmpty()) throw error(HttpStatus.CONFLICT, "Pro payment already pending");
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " payment_orders(id,account_id,reason,amount,idempotency_key,fingerprint)"
                    + " VALUES (?,?,'PRO',?,?,?)",
                id,
                account,
                pro,
                key,
                fp);
        line(id, "PRO_MONTHLY", pro);
        db.update(
                "UPDATE subscriptions SET method_id=?,auto_renew=? WHERE account_id=?",
                method,
                auto,
                account);
        return publicOrder(order(id));
    }

    public Map<String, Object> entitlement(UUID account) {
        var periods =
                db.queryForList(
                        "SELECT starts_at,ends_at FROM subscription_periods WHERE account_id=?"
                            + " ORDER BY ends_at DESC LIMIT 1",
                        account);
        boolean active = false;
        Object end = null;
        if (!periods.isEmpty()) {
            var p = periods.getFirst();
            end = p.get("ends_at");
            active =
                    BillingPolicy.covers(
                            ((java.sql.Timestamp) p.get("starts_at")).toInstant(),
                            ((java.sql.Timestamp) end).toInstant(),
                            Instant.now());
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("accountId", account);
        m.put("proActive", active);
        m.put("plan", active ? "PRO" : "FREE");
        m.put("periodEndsAt", end);
        return m;
    }

    @Transactional
    public Map<String, Object> confirm(UUID id, String reference) {
        lock(id.toString());
        var o = order(id);
        var result = gateway.verify(reference, id, (BigDecimal) o.get("amount"));
        var existing =
                db.queryForList(
                        "SELECT * FROM payment_transactions WHERE provider_reference=?", reference);
        if (!existing.isEmpty() && !id.equals(existing.getFirst().get("order_id")))
            throw error(HttpStatus.CONFLICT, "Provider reference reused");
        if ("CONFIRMED".equals(o.get("status"))) {
            if (existing.isEmpty())
                throw error(HttpStatus.CONFLICT, "Order already paid with another reference");
            return publicOrder(o);
        }
        if (existing.isEmpty())
            db.update(
                    "INSERT INTO payment_transactions(id,order_id,provider_reference,status,amount)"
                        + " VALUES (?,?,?,?,?)",
                    UUID.randomUUID(),
                    id,
                    reference,
                    result.status(),
                    result.amount());
        else
            db.update(
                    "UPDATE payment_transactions SET status=? WHERE provider_reference=?",
                    result.status(),
                    reference);
        db.update(
                "UPDATE payment_orders SET status=?,version=version+1 WHERE id=?",
                result.status(),
                id);
        if (result.status().equals("CONFIRMED")) {
            Instant now = Instant.now();
            db.update(
                    "UPDATE payment_orders SET confirmed_at=? WHERE id=?",
                    java.sql.Timestamp.from(now),
                    id);
            if ("PRO".equals(o.get("reason"))) {
                UUID account = (UUID) o.get("account_id");
                lock(account.toString());
                db.update(
                        "INSERT INTO subscription_periods VALUES (?,?,?,?,?)",
                        UUID.randomUUID(),
                        account,
                        id,
                        java.sql.Timestamp.from(now),
                        java.sql.Timestamp.from(BillingPolicy.monthEnd(now)));
            }
            event("PaymentConfirmed", id, "{}");
            db.update(
                    "INSERT INTO billing_documents(id,order_id,series,number) VALUES"
                        + " (?,?,'B001',nextval('fiscal_number'))",
                    UUID.randomUUID(),
                    id);
        }
        return publicOrder(order(id));
    }

    private void event(String type, UUID aggregate, String payload) {
        db.update(
                "INSERT INTO billing_audit VALUES (?,?,?, ?,now())",
                UUID.randomUUID(),
                null,
                type,
                aggregate);
        db.update(
                "INSERT INTO outbox(id,event_type,aggregate_id,payload,correlation_id) VALUES"
                    + " (?,?,?,?,?)",
                UUID.randomUUID(),
                type,
                aggregate,
                payload,
                UUID.randomUUID());
    }

    @Transactional
    public void installed(UUID event, UUID account, Instant at, String fingerprint) {
        if (!receipt(event, fingerprint)) return;
        if (db.queryForList(
                        "SELECT id FROM payment_orders WHERE account_id=? AND reason='INITIAL' AND"
                            + " status='CONFIRMED'",
                        account)
                .isEmpty()) throw error(HttpStatus.CONFLICT, "Verified purchase required");
        db.update(
                "INSERT INTO subscriptions(account_id,installed_at) VALUES (?,?) ON"
                    + " CONFLICT(account_id) DO NOTHING",
                account,
                java.sql.Timestamp.from(at));
    }

    private boolean receipt(UUID event, String fp) {
        lock(event.toString());
        var old =
                db.queryForList(
                        "SELECT fingerprint FROM integration_inbox WHERE event_id=?", event);
        if (!old.isEmpty()) {
            if (!fp.equals(old.getFirst().get("fingerprint")))
                throw error(HttpStatus.CONFLICT, "Event conflict");
            return false;
        }
        db.update("INSERT INTO integration_inbox(event_id,fingerprint) VALUES (?,?)", event, fp);
        return true;
    }

    @Transactional
    public void visit(
            UUID event, UUID visit, UUID account, UUID meter, Instant at, BigDecimal cost) {
        BillingPolicy.money(cost);
        if (at.isAfter(Instant.now()))
            throw error(HttpStatus.BAD_REQUEST, "Visit completion is in the future");
        String fp = hash(visit + "|" + account + "|" + meter + "|" + at + "|" + cost);
        if (!receipt(event, fp)) return;
        lock(visit.toString());
        if (!db.queryForList("SELECT visit_id FROM coverage_decisions WHERE visit_id=?", visit)
                .isEmpty()) throw error(HttpStatus.CONFLICT, "Visit already billed");
        boolean covered =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM subscription_periods WHERE"
                                    + " account_id=? AND starts_at<=? AND ends_at>?)",
                                Boolean.class,
                                account,
                                java.sql.Timestamp.from(at),
                                java.sql.Timestamp.from(at)));
        UUID order = null;
        if (!covered && cost.signum() > 0) {
            order = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " payment_orders(id,account_id,reason,amount,idempotency_key,fingerprint)"
                        + " VALUES (?,?,'TECHNICAL',?,?,?)",
                    order,
                    account,
                    cost,
                    "visit:" + visit,
                    fp);
            line(order, "TECHNICAL_VISIT", cost);
        }
        db.update(
                "INSERT INTO coverage_decisions VALUES (?,?,?,?,?,?,?,?)",
                visit,
                account,
                meter,
                java.sql.Timestamp.from(at),
                cost,
                covered,
                covered ? BigDecimal.ZERO : cost,
                order);
        event("CoverageCalculated", visit, "{\"covered\":" + covered + "}");
    }

    public List<Map<String, Object>> history(IdentityPort.Account actor, int offset, int limit) {
        UUID account = principal(actor);
        if (offset < 0 || limit < 1 || limit > 100)
            throw error(HttpStatus.BAD_REQUEST, "Invalid pagination");
        return db.queryForList(
                "SELECT id,reason,amount,currency,status,created_at,confirmed_at FROM"
                    + " payment_orders WHERE account_id=? ORDER BY created_at DESC,id LIMIT ?"
                    + " OFFSET ?",
                account,
                limit,
                offset);
    }

    public Map<String, Object> document(IdentityPort.Account actor, UUID id) {
        UUID account = principal(actor);
        return db
                .queryForList(
                        "SELECT d.* FROM billing_documents d JOIN payment_orders o ON"
                            + " o.id=d.order_id WHERE d.id=? AND o.account_id=?",
                        id,
                        account)
                .stream()
                .findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Document not found"));
    }
}
