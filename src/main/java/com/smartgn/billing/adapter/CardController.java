package com.smartgn.billing.adapter;

import com.smartgn.billing.application.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@RestController
@Profile("local")
public class CardController {
    private final BillingService service;
    private final JdbcTemplate db;
    private final byte[] key;

    public CardController(
            BillingService service,
            JdbcTemplate db,
            @Value("${smartgn.internal-token}") String secret) {
        this.service = service;
        this.db = db;
        this.key = HexFormat.of().parseHex(BillingService.hash(secret));
    }

    public record Card(
            @Pattern(regexp = "test_[a-zA-Z0-9_-]{16,160}") @NotNull String providerToken,
            @NotBlank @Size(max = 40) String brand,
            @Pattern(regexp = "[0-9]{4}") @NotNull String lastFour) {}

    private String protect(String value) {
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted =
                    cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(
                            java.nio.ByteBuffer.allocate(iv.length + encrypted.length)
                                    .put(iv)
                                    .put(encrypted)
                                    .array());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PostMapping("/api/v1/payment-methods")
    @Transactional
    public Object add(
            @RequestAttribute("actor") IdentityPort.Account actor, @Valid @RequestBody Card r) {
        UUID account = service.principal(actor), id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " payment_methods(id,account_id,protected_token,token_hash,brand,last_four)"
                    + " VALUES (?,?,?,?,?,?)",
                id,
                account,
                protect(r.providerToken()),
                BillingService.hash(r.providerToken()),
                r.brand(),
                r.lastFour());
        return Map.of("id", id, "testMode", true);
    }

    @GetMapping("/api/v1/payment-methods")
    public Object list(@RequestAttribute("actor") IdentityPort.Account actor) {
        return db.queryForList(
                "SELECT id,brand,last_four,is_default,active FROM payment_methods WHERE"
                    + " account_id=?",
                service.principal(actor));
    }

    @PutMapping("/api/v1/payment-methods/{id}/default")
    @Transactional
    public void primary(
            @RequestAttribute("actor") IdentityPort.Account actor, @PathVariable UUID id) {
        UUID a = service.principal(actor);
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", a.toString());
        if (db.queryForList(
                        "SELECT id FROM payment_methods WHERE id=? AND account_id=? AND active",
                        id,
                        a)
                .isEmpty())
            throw BillingService.error(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Card unavailable");
        db.update("UPDATE payment_methods SET is_default=false WHERE account_id=?", a);
        db.update("UPDATE payment_methods SET is_default=true WHERE id=?", id);
    }

    @DeleteMapping("/api/v1/payment-methods/{id}")
    @Transactional
    public void delete(
            @RequestAttribute("actor") IdentityPort.Account actor, @PathVariable UUID id) {
        UUID a = service.principal(actor);
        if (db.update(
                        "UPDATE payment_methods SET active=false,is_default=false WHERE id=? AND"
                            + " account_id=?",
                        id,
                        a)
                == 0)
            throw BillingService.error(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Card unavailable");
        db.update(
                "UPDATE subscriptions SET auto_renew=false WHERE account_id=? AND method_id=?",
                a,
                id);
    }
}
