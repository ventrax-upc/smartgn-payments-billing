package com.ventrax.payments.adapter;

import com.ventrax.payments.application.BillingService;
import com.ventrax.payments.application.PaymentGateway;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class NiubizSandbox {
    private static final String ROOT = "https://apisandbox.vnforappstest.com";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String merchant;
    private final String basic;

    public NiubizSandbox(JdbcTemplate db, TransactionTemplate transactions, ObjectMapper json,
            @Value("${NIUBIZ_SANDBOX_ENABLED:false}") boolean enabled,
            @Value("${NIUBIZ_SANDBOX_MERCHANT:}") String merchant,
            @Value("${NIUBIZ_SANDBOX_USER:}") String user,
            @Value("${NIUBIZ_SANDBOX_PASSWORD:}") String password) {
        this.db = db;
        this.transactions = transactions;
        this.json = json;
        this.enabled = enabled;
        this.merchant = merchant;
        this.basic = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
        if (enabled && (!merchant.matches("[0-9]{9}") || user.isBlank() || password.isBlank()))
            throw new IllegalStateException("Niubiz sandbox credentials are incomplete");
    }

    public boolean enabled() {
        return enabled;
    }

    @Transactional
    public Map<String, Object> session(UUID id, Map<String, Object> order) {
        lock(id);
        String status = status(id);
        if (!"PENDING".equals(status) && !"DECLINED".equals(status)) {
            throw BillingService.error(HttpStatus.CONFLICT, "Order cannot start a new payment session");
        }
        boolean retry = "DECLINED".equals(status);
        BigDecimal amount = (BigDecimal) order.get("amount");
        String email = db.queryForObject("SELECT email FROM payment_orders WHERE id=?", String.class, id);
        var res = post("/api.ecommerce/v2/ecommerce/token/session/" + merchant, security(),
                Map.of("amount", amount, "channel", "web", "antifraud", Map.of("clientIp", "127.0.0.1",
                        "merchantDefineData", Map.of("MDD4", email, "MDD21", "0"))));
        String key = res.statusCode() == 200 ? json.readTree(res.body()).path("sessionKey").asText() : "";
        if (key.isBlank()) throw BillingService.error(HttpStatus.SERVICE_UNAVAILABLE, "Niubiz session unavailable");
        var numbers = db.queryForList("""
                INSERT INTO niubiz_sandbox_checkouts(order_id,expires_at) VALUES (?,now()+interval '10 minutes')
                ON CONFLICT (order_id) DO UPDATE SET expires_at=excluded.expires_at,
                    purchase_number=CASE WHEN ? THEN excluded.purchase_number
                        ELSE niubiz_sandbox_checkouts.purchase_number END,
                    token_hash=NULL
                WHERE niubiz_sandbox_checkouts.token_hash IS NULL OR ?
                RETURNING purchase_number
                """, Long.class, id, retry, retry);
        if (numbers.isEmpty()) throw BillingService.error(HttpStatus.CONFLICT, "Sandbox authorization already submitted");
        if (retry) {
            db.update("UPDATE payment_orders SET status='PENDING',version=version+1 WHERE id=?", id);
        }
        return Map.of("provider", "NIUBIZ_SANDBOX", "testMode", true, "sessionKey", key, "merchantId", merchant,
                "purchaseNumber", numbers.getFirst().toString(), "amount", amount);
    }

    public Map<String, Object> authorize(UUID id, Map<String, Object> order, String token, BillingService billing) {
        if (!enabled) throw BillingService.error(HttpStatus.SERVICE_UNAVAILABLE, "Niubiz sandbox is disabled");
        if (!"PENDING".equals(order.get("status"))) return order;
        String auth = security();
        Long purchase = transactions.execute(transaction -> {
            lock(id);
            requirePending(id);
            String tokenHash = fingerprint(token);
            int inserted = db.update("""
                    INSERT INTO niubiz_sandbox_used_tokens(token_hash) VALUES (?) ON CONFLICT DO NOTHING
                    """, tokenHash);
            if (inserted == 0) throw BillingService.error(HttpStatus.CONFLICT, "Use a fresh Niubiz token");
            var claimed = db.queryForList("""
                    UPDATE niubiz_sandbox_checkouts SET token_hash=?
                    WHERE order_id=? AND token_hash IS NULL AND expires_at>now()
                    RETURNING purchase_number
                    """, Long.class, tokenHash, id);
            if (claimed.isEmpty())
                throw BillingService.error(HttpStatus.CONFLICT, "Start a session or check the existing payment status");
            db.update("UPDATE payment_orders SET status='UNKNOWN',version=version+1 WHERE id=?", id);
            return claimed.getFirst();
        });
        BigDecimal amount = (BigDecimal) order.get("amount");
        PaymentGateway.Result result;
        try {
            var response = post("/api.authorization/v3/authorization/ecommerce/" + merchant, auth,
                    Map.of("channel", "web", "captureType", "manual", "countable", true,
                            "order", Map.of("amount", amount, "currency", "PEN", "purchaseNumber", purchase.toString(),
                                    "tokenId", token)));
            result = parseResult(response, purchase, amount);
        } catch (RuntimeException uncertain) {
            result = unknown(purchase, amount);
        }
        return billing.confirmSandbox(id, result);
    }

    private PaymentGateway.Result parseResult(HttpResponse<String> response, Long purchase, BigDecimal amount) {
        JsonNode data = json.readTree(response.body());
        JsonNode order = data.path("order");
        String reference = order.path("transactionId").asText();
        if (response.statusCode() == 200 && "000".equals(order.path("actionCode").asText())
                && purchase.toString().equals(order.path("purchaseNumber").asText())
                && "PEN".equals(order.path("currency").asText())
                && amount.compareTo(new BigDecimal(order.path("authorizedAmount").asText())) == 0
                && reference.matches("[A-Za-z0-9_-]{1,100}")) {
            return new PaymentGateway.Result("niubiz-sandbox:" + reference, "CONFIRMED", amount, true);
        }
        JsonNode failure = data.path("data");
        String action = failure.path("ACTION_CODE").asText();
        if (response.statusCode() == 400 && merchant.equals(failure.path("MERCHANT").asText())
                && action.matches("[0-9]{3}") && !"000".equals(action)
                && "Not Authorized".equalsIgnoreCase(failure.path("STATUS").asText())) {
            return new PaymentGateway.Result("niubiz-sandbox-declined:" + purchase, "DECLINED", amount, true);
        }
        return unknown(purchase, amount);
    }

    private PaymentGateway.Result unknown(Long purchase, BigDecimal amount) {
        return new PaymentGateway.Result("niubiz-sandbox-unknown:" + purchase, "UNKNOWN", amount, true);
    }

    private void lock(UUID id) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", id.toString());
    }

    private void requirePending(UUID id) {
        if (!"PENDING".equals(status(id))) throw BillingService.error(HttpStatus.CONFLICT, "Order is not pending payment");
    }

    private String status(UUID id) {
        return db.queryForObject("SELECT status FROM payment_orders WHERE id=? AND reason='INITIAL'", String.class, id);
    }

    private String security() {
        var request = HttpRequest.newBuilder(URI.create(ROOT + "/api.security/v1/security"))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", basic)
                .GET()
                .build();
        var result = send(request);
        if (result.statusCode() != 201 || result.body().isBlank())
            throw BillingService.error(HttpStatus.SERVICE_UNAVAILABLE, "Niubiz authentication unavailable");
        return result.body().strip();
    }

    private HttpResponse<String> post(String path, String authorization, Map<String, ?> body) {
        var request = HttpRequest.newBuilder(URI.create(ROOT + path))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw BillingService.error(HttpStatus.SERVICE_UNAVAILABLE, "Niubiz request interrupted");
        } catch (java.io.IOException failure) {
            throw BillingService.error(HttpStatus.SERVICE_UNAVAILABLE, "Niubiz is unavailable");
        }
    }

    private static String fingerprint(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
