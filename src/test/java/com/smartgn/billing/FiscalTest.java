package com.smartgn.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.smartgn.billing.adapter.*;

import org.junit.jupiter.api.*;

import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMValidateContext;

class FiscalTest {
    static TaxConfiguration cfg;

    @BeforeAll
    static void key() throws Exception {
        Path path = Files.createTempDirectory("billing-tax-test").resolve("key.p12");
        var p =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "test",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-validity",
                                "2",
                                "-dname",
                                "CN=LOCAL TEST",
                                "-keystore",
                                path.toString(),
                                "-storetype",
                                "PKCS12",
                                "-storepass",
                                "test-password")
                        .redirectErrorStream(true)
                        .start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        cfg =
                new TaxConfiguration(
                        "20100066603",
                        "SmartGN TEST",
                        "Lima TEST",
                        "150101",
                        path.toString(),
                        "test-password",
                        "local",
                        new BigDecimal("0.18"));
    }

    byte[] invoice() {
        return new FiscalXml(cfg)
                .invoice(
                        "B001-1",
                        Instant.parse("2026-10-28T15:00:00Z"),
                        new BigDecimal("250.00"),
                        List.of(
                                Map.of("concept", "DEVICE", "amount", new BigDecimal("200.00")),
                                Map.of(
                                        "concept",
                                        "INSTALLATION",
                                        "amount",
                                        new BigDecimal("50.00"))),
                        "Cliente & Ejemplo");
    }

    boolean valid(byte[] bytes) throws Exception {
        var doc = XmlSupport.parse(bytes);
        var sig = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        var context = new DOMValidateContext(cfg.signingKey().getCertificate().getPublicKey(), sig);
        return XMLSignatureFactory.getInstance("DOM")
                .unmarshalXMLSignature(context)
                .validate(context);
    }

    @Test
    void signatureAndInvoiceTotalsAreValid() throws Exception {
        byte[] xml = invoice();
        assertTrue(valid(xml));
        var doc = XmlSupport.parse(xml);
        assertEquals("2.1", XmlSupport.first(doc, "UBLVersionID"));
        assertEquals("250.00", XmlSupport.first(doc, "PayableAmount"));
        var bases = doc.getElementsByTagNameNS(FiscalXml.CBC, "LineExtensionAmount");
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 1; i < bases.getLength(); i++)
            sum = sum.add(new BigDecimal(bases.item(i).getTextContent()));
        assertEquals(new BigDecimal(bases.item(0).getTextContent()), sum);
    }

    @Test
    void tamperingInvalidatesSignature() throws Exception {
        byte[] xml = invoice();
        String tampered =
                new String(xml, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("Cliente &amp; Ejemplo", "Different Customer");
        assertFalse(valid(tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void signedSummaryCanBeSubmittedAndCdrRetrieved() throws Exception {
        byte[] xml =
                new FiscalXml(cfg)
                        .summary(
                                "RC-20261028-1", "B001-1", Instant.now(), new BigDecimal("250.00"));
        assertTrue(valid(xml));
        java.nio.file.Files.write(java.nio.file.Path.of("target/test-summary.xml"), xml);
        var adapter = new LocalTaxGateway();
        var result =
                adapter.submit("20100066603-RC-20261028-1", XmlSupport.zip("summary.xml", xml));
        assertEquals("SUBMITTED", result.status());
        var response = adapter.poll(result.ticket());
        assertEquals("LOCAL_ACCEPTED", response.status());
        assertEquals(
                "0",
                XmlSupport.first(
                        XmlSupport.parse(XmlSupport.unzipXml(response.cdr())), "ResponseCode"));
    }

    @Test
    void pdfIsReadableAndClearlyMarked() throws Exception {
        byte[] pdf =
                new FiscalPdf()
                        .render(
                                "B001-1",
                                "SmartGN TEST",
                                "20100066603",
                                "Mar\u00eda P\u00e9rez",
                                new BigDecimal("250.00"),
                                List.of(
                                        Map.of(
                                                "concept",
                                                "DEVICE",
                                                "amount",
                                                new BigDecimal("250.00"))),
                                true,
                                "ISSUED_TEST");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            String content = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertTrue(content.contains("SIN VALIDEZ TRIBUTARIA"));
            assertTrue(content.contains("Mar\u00eda P\u00e9rez"));
        }
        Path out = Path.of("target/fiscal-preview.pdf");
        Files.write(out, pdf);
    }

    @Test
    void xmlExternalEntitiesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        XmlSupport.parse(
                                "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><x>&secret;</x>"
                                        .getBytes()));
    }

    @Test
    void betaAdapterRejectsProductionEndpoint() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SunatBetaGateway(
                                "https://e-factura.sunat.gob.pe/billService", "user", "password"));
    }
}
