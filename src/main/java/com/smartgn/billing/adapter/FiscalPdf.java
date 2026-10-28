package com.smartgn.billing.adapter;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;

public class FiscalPdf {
    public byte[] render(
            String id,
            String issuer,
            String ruc,
            String buyer,
            BigDecimal amount,
            List<Map<String, Object>> lines,
            boolean testMode,
            String fiscalStatus) {
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var text = new PDPageContentStream(doc, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                text.newLineAtOffset(50, 740);
                List<String> rows = new ArrayList<>();
                rows.add("SmartGN - Boleta de venta electronica");
                rows.add(id);
                if (testMode) rows.add("PRUEBA - SIN VALIDEZ TRIBUTARIA");
                rows.add("Emisor: " + issuer);
                rows.add("RUC: " + ruc);
                rows.add("Cliente: " + buyer);
                rows.add("");
                for (var l : lines) rows.add(l.get("concept") + "   PEN " + l.get("amount"));
                rows.add("Total final: PEN " + amount);
                rows.add("Estado al generar: " + fiscalStatus);
                rows.add("Consultar estado tributario y CDR actualizados en SmartGN.");
                for (String row : rows) {
                    String safe = row.replaceAll("[^ -~\u00A0-\u00FF]", "?");
                    for (int start = 0; start < Math.max(1, safe.length()); start += 80) {
                        text.showText(safe.substring(start, Math.min(start + 80, safe.length())));
                        text.newLineAtOffset(0, -22);
                    }
                }
                text.endText();
            }
            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF generation failed", e);
        }
    }
}
