package com.smartgn.billing.adapter;

import com.smartgn.billing.application.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@RestController
public class CardController {
    private final BillingService service;
    private final JdbcTemplate db;
    private final PaymentGateway provider;
    private final TokenProtector tokens;

    public CardController(
            BillingService service,
            JdbcTemplate db,
            PaymentGateway provider,
            TokenProtector tokens) {
        this.service = service;
        this.db = db;
        this.provider = provider;
        this.tokens = tokens;
    }

    public record Card(
            @NotBlank @Size(max = 255) String providerToken,
            @Size(max = 40) String brand,
            @Pattern(regexp = "[0-9]{4}") String lastFour) {}

    @PostMapping("/api/v1/payment-methods")
    @Transactional
    public Object add(
            @RequestAttribute("actor") IdentityPort.Account actor, @Valid @RequestBody Card r) {
        UUID account = service.principal(actor), id = UUID.randomUUID();
        var proof = provider.card(r.providerToken(), account);
        if ((r.brand() != null && !r.brand().equals(proof.brand()))
                || (r.lastFour() != null && !r.lastFour().equals(proof.lastFour())))
            throw BillingService.error(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Provider card metadata mismatch");
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", account.toString());
        var existing =
                db.queryForList(
                        "SELECT id FROM payment_methods WHERE token_hash=? AND account_id=? AND"
                                + " active",
                        BillingService.hash(r.providerToken()),
                        account);
        if (!existing.isEmpty())
            return Map.of("id", existing.getFirst().get("id"), "testMode", proof.testMode());
        db.update(
                "INSERT INTO"
                    + " payment_methods(id,account_id,protected_token,token_hash,brand,last_four,recurring_authorized)"
                    + " VALUES (?,?,?,?,?,?,?)",
                id,
                account,
                tokens.protect(r.providerToken()),
                BillingService.hash(r.providerToken()),
                proof.brand(),
                proof.lastFour(),
                proof.recurringAuthorized());
        com.smartgn.billing.configuration.Trace.audit(db, account, "CARD_REGISTERED", id);
        return Map.of("id", id, "testMode", proof.testMode());
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
        com.smartgn.billing.configuration.Trace.audit(db, a, "CARD_DEFAULT_CHANGED", id);
    }

    @DeleteMapping("/api/v1/payment-methods/{id}")
    @Transactional
    public void delete(
            @RequestAttribute("actor") IdentityPort.Account actor, @PathVariable UUID id) {
        UUID a = service.principal(actor);
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", a.toString());
        if (!db.queryForList(
                        "SELECT j.id FROM payment_jobs j WHERE j.method_id=? AND j.status IN"
                                + " ('PENDING','UNKNOWN')",
                        id)
                .isEmpty())
            throw BillingService.error(
                    org.springframework.http.HttpStatus.CONFLICT, "Card has an unresolved payment");
        com.smartgn.billing.configuration.Trace.audit(db, a, "CARD_UNLINKED", id);
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
