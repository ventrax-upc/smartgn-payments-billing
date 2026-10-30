package com.ventrax.payments.application;

import com.ventrax.payments.adapter.*;
import com.ventrax.payments.configuration.Trace;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class FiscalService {
    private final JdbcTemplate db;
    private final TaxConfiguration cfg;
    private final TaxGateway gateway;
    private final BillingService billing;

    public FiscalService(
            JdbcTemplate db, TaxConfiguration cfg, TaxGateway gateway, BillingService billing) {
        this.db = db;
        this.cfg = cfg;
        this.gateway = gateway;
        this.billing = billing;
    }

    @Transactional
    public boolean prepare(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT d.*,o.amount,o.account_id,o.first_name,o.last_name,o.confirmed_at"
                            + " FROM billing_documents d JOIN payment_orders o ON o.id=d.order_id"
                            + " WHERE d.id=? FOR UPDATE OF d",
                        id);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        if (row.get("xml") != null) return true;
        if (!cfg.configured()) return false;
        String buyer =
                row.get("first_name") == null
                        ? "Cliente SmartGN"
                        : row.get("first_name") + " " + row.get("last_name");
        if (row.get("account_id") != null && row.get("first_name") == null) {
            var original =
                    db.queryForList(
                            "SELECT first_name,last_name FROM payment_orders WHERE account_id=? AND"
                                    + " reason='INITIAL' AND status='CONFIRMED' LIMIT 1",
                            row.get("account_id"));
            if (!original.isEmpty())
                buyer =
                        original.getFirst().get("first_name")
                                + " "
                                + original.getFirst().get("last_name");
        }
        var lines =
                db.queryForList(
                        "SELECT concept,amount FROM order_lines WHERE order_id=? ORDER BY concept",
                        row.get("order_id"));
        BigDecimal total = (BigDecimal) row.get("amount");
        String docId = row.get("series") + "-" + row.get("number");
        Instant issued = Instant.now();
        var engine = new FiscalXml(cfg);
        byte[] xml = engine.invoice(docId, issued, total, lines, buyer);
        byte[] pdf =
                new FiscalPdf()
                        .render(
                                docId,
                                cfg.name,
                                cfg.ruc,
                                buyer,
                                total,
                                lines,
                                gateway.testMode(),
                                "ISSUED_TEST");
        UUID summary = UUID.randomUUID();
        java.time.LocalDate issueDay =
                issued.atZone(java.time.ZoneId.of("America/Lima")).toLocalDate();
        Integer dailyNumber =
                db.queryForObject(
                        "INSERT INTO fiscal_daily_sequences(issue_day,value) VALUES (?,1) ON"
                                + " CONFLICT(issue_day) DO UPDATE SET"
                                + " value=fiscal_daily_sequences.value+1 RETURNING value",
                        Integer.class,
                        java.sql.Date.valueOf(issueDay));
        String summaryId =
                "RC-"
                        + issued.atZone(java.time.ZoneId.of("America/Lima"))
                                .toLocalDate()
                                .toString()
                                .replace("-", "")
                        + "-"
                        + dailyNumber;
        String name = cfg.ruc + "-" + summaryId;
        byte[] summaryXml = engine.summary(summaryId, docId, issued, total);
        db.update(
                "UPDATE billing_documents SET"
                    + " xml=?,pdf=?,issued_at=?,test_mode=?,fiscal_status='ISSUED_TEST',summary_id=?"
                    + " WHERE id=?",
                new String(xml, StandardCharsets.UTF_8),
                pdf,
                java.sql.Timestamp.from(issued),
                gateway.testMode(),
                summary,
                id);
        db.update(
                "INSERT INTO fiscal_summaries(id,document_id,name,xml,status) VALUES"
                        + " (?,?,?,?,'PENDING')",
                summary,
                id,
                name,
                new String(summaryXml, StandardCharsets.UTF_8));
        event(id, "BillingDocumentIssued", row.get("account_id"));
        return true;
    }

    @Transactional
    public void accept(UUID summary, UUID claim, TaxGateway.Receipt receipt) {
        var jobs =
                db.queryForList(
                        "SELECT * FROM fiscal_summaries WHERE id=? AND lease_id=? AND lease_until"
                                + " IS NOT NULL FOR UPDATE",
                        summary,
                        claim);
        if (jobs.isEmpty()) return;
        var j = jobs.getFirst();
        String message = receipt.description() == null ? "" : receipt.description();
        if (message.length() > 2000) message = message.substring(0, 2000);
        db.update(
                "UPDATE fiscal_summaries SET"
                    + " ticket=?,status=?,response=?,cdr=?,lease_until=NULL,available_at=now()+interval"
                    + " '3 seconds' WHERE id=?",
                receipt.ticket(),
                receipt.status(),
                message,
                receipt.cdr(),
                summary);
        db.update(
                "UPDATE billing_documents SET fiscal_status=?,response=?,cdr=? WHERE id=?",
                receipt.status(),
                message,
                receipt.cdr(),
                j.get("document_id"));
        if (!receipt.status().equals("SUBMITTED"))
            event((UUID) j.get("document_id"), "BillingDocumentTaxStatusChanged", null);
    }

    public void event(UUID document, String type, Object account) {
        String payload =
                new tools.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "documentId",
                                        document,
                                        "accountId",
                                        account == null ? "PENDING_PROVISIONING" : account,
                                        "testMode",
                                        true));
        db.update(
                "INSERT INTO outbox(id,event_type,aggregate_id,payload,correlation_id) VALUES"
                        + " (?,?,?,?,?)",
                UUID.randomUUID(),
                type,
                document,
                payload,
                Trace.correlation());
        Trace.audit(db, null, type, document);
    }

    public byte[] download(IdentityPort.Account actor, UUID id, String format) {
        billing.document(actor, id);
        String field =
                switch (format) {
                    case "xml" -> "xml";
                    case "pdf" -> "pdf";
                    case "cdr" -> "cdr";
                    default ->
                            throw BillingService.error(
                                    HttpStatus.BAD_REQUEST, "Unknown artifact format");
                };
        var r =
                db.queryForMap("SELECT " + field + " FROM billing_documents WHERE id=?", id)
                        .get(field);
        if (r == null)
            throw BillingService.error(HttpStatus.CONFLICT, "Artifact not available yet");
        return r instanceof byte[] b ? b : r.toString().getBytes(StandardCharsets.UTF_8);
    }
}
