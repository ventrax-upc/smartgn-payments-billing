package com.ventrax.payments.adapter;

import com.ventrax.payments.application.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class FiscalWorker {
    private final JdbcTemplate db;
    private final FiscalService fiscal;
    private final TaxGateway gateway;

    public FiscalWorker(JdbcTemplate db, FiscalService fiscal, TaxGateway gateway) {
        this.db = db;
        this.fiscal = fiscal;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${smartgn.worker-delay:1000}")
    public void run() {
        for (var d : db.queryForList("SELECT id FROM billing_documents WHERE xml IS NULL LIMIT 5"))
            try {
                fiscal.prepare((UUID) d.get("id"));
            } catch (Exception e) {
                db.update(
                        "UPDATE billing_documents SET response='Signing or fiscal configuration"
                                + " unavailable' WHERE id=?",
                        d.get("id"));
            }
        UUID claim = UUID.randomUUID();
        var jobs =
                db.queryForList(
                        "UPDATE fiscal_summaries SET lease_id=?,lease_until=now()+interval '45"
                            + " seconds' WHERE id=(SELECT id FROM fiscal_summaries WHERE status IN"
                            + " ('PENDING','SUBMITTED') AND available_at<=now() AND (lease_until IS"
                            + " NULL OR lease_until<now()) ORDER BY available_at LIMIT 1 FOR UPDATE"
                            + " SKIP LOCKED) RETURNING *",
                        claim);
        for (var j : jobs)
            try {
                TaxGateway.Receipt r =
                        j.get("ticket") == null
                                ? gateway.submit(
                                        j.get("name").toString(),
                                        XmlSupport.zip(
                                                j.get("name") + ".xml",
                                                j.get("xml")
                                                        .toString()
                                                        .getBytes(StandardCharsets.UTF_8)))
                                : gateway.poll(j.get("ticket").toString());
                fiscal.accept((UUID) j.get("id"), claim, r);
            } catch (TaxGateway.Rejected rejected) {
                fiscal.accept(
                        (UUID) j.get("id"),
                        claim,
                        new TaxGateway.Receipt(
                                "TEST_REJECTED",
                                j.get("ticket") == null ? null : j.get("ticket").toString(),
                                rejected.code + ": " + rejected.getMessage(),
                                null));
            } catch (Exception e) {
                db.update(
                        "UPDATE fiscal_summaries SET attempts=attempts+1,response='External"
                            + " response unavailable; stable summary"
                            + " retained',lease_until=NULL,available_at=now()+interval '30 seconds'"
                            + " WHERE id=? AND lease_id=?",
                        j.get("id"),
                        claim);
            }
    }
}
