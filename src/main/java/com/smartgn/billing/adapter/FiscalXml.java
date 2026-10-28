package com.smartgn.billing.adapter;

import org.w3c.dom.*;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.*;
import java.util.*;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.*;

public class FiscalXml {
    public static final String
            CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2",
            CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2",
            EXT = "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2";
    private final TaxConfiguration cfg;

    public FiscalXml(TaxConfiguration cfg) {
        this.cfg = cfg;
    }

    private String c(String name, Object text) {
        return "<cbc:" + name + ">" + XmlSupport.escape(text) + "</cbc:" + name + ">";
    }

    private String amount(String name, BigDecimal value) {
        return "<cbc:"
                + name
                + " currencyID='PEN'>"
                + value.setScale(2, RoundingMode.HALF_UP)
                + "</cbc:"
                + name
                + ">";
    }

    private String head(String root, String ns) {
        return "<"
                + root
                + " xmlns='"
                + ns
                + "' xmlns:cbc='"
                + CBC
                + "' xmlns:cac='"
                + CAC
                + "' xmlns:ext='"
                + EXT
                + "'><ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent/></ext:UBLExtension></ext:UBLExtensions>";
    }

    private String signature() {
        return "<cac:Signature>"
                + c("ID", "SignatureSP")
                + "<cac:SignatoryParty><cac:PartyIdentification>"
                + c("ID", cfg.ruc)
                + "</cac:PartyIdentification><cac:PartyName>"
                + c("Name", cfg.name)
                + "</cac:PartyName></cac:SignatoryParty><cac:DigitalSignatureAttachment><cac:ExternalReference>"
                + c("URI", "#SignatureSP")
                + "</cac:ExternalReference></cac:DigitalSignatureAttachment></cac:Signature>";
    }

    private String supplier(boolean summary) {
        String party =
                "<cac:Party><cac:PartyIdentification><cbc:ID schemeID='6'>"
                        + cfg.ruc
                        + "</cbc:ID></cac:PartyIdentification><cac:PartyName>"
                        + c("Name", cfg.name)
                        + "</cac:PartyName><cac:PartyLegalEntity>"
                        + c("RegistrationName", cfg.name)
                        + "<cac:RegistrationAddress>"
                        + c("ID", cfg.ubigeo)
                        + "<cbc:AddressTypeCode>0000</cbc:AddressTypeCode><cac:AddressLine>"
                        + c("Line", cfg.address)
                        + "</cac:AddressLine><cac:Country>"
                        + c("IdentificationCode", "PE")
                        + "</cac:Country></cac:RegistrationAddress></cac:PartyLegalEntity></cac:Party>";
        return "<cac:AccountingSupplierParty>"
                + (summary
                        ? c("CustomerAssignedAccountID", cfg.ruc) + c("AdditionalAccountID", "6")
                        : "")
                + party
                + "</cac:AccountingSupplierParty>";
    }

    private BigDecimal net(BigDecimal total) {
        return total.divide(BigDecimal.ONE.add(cfg.rate), 2, RoundingMode.HALF_UP);
    }

    private String scheme() {
        return "<cac:TaxScheme>"
                + c("ID", cfg.rate.signum() == 0 ? "9997" : "1000")
                + c("Name", cfg.rate.signum() == 0 ? "EXO" : "IGV")
                + c("TaxTypeCode", cfg.rate.signum() == 0 ? "VAT" : "VAT")
                + "</cac:TaxScheme>";
    }

    private String tax(BigDecimal total) {
        BigDecimal base = net(total), tax = total.subtract(base);
        return "<cac:TaxTotal>"
                + amount("TaxAmount", tax)
                + "<cac:TaxSubtotal>"
                + amount("TaxableAmount", base)
                + amount("TaxAmount", tax)
                + "<cac:TaxCategory>"
                + c("Percent", cfg.rate.multiply(new BigDecimal("100")))
                + scheme()
                + "</cac:TaxCategory></cac:TaxSubtotal></cac:TaxTotal>";
    }

    public byte[] invoice(
            String id,
            Instant issued,
            BigDecimal total,
            List<Map<String, Object>> lines,
            String customer) {
        var date = issued.atZone(ZoneId.of("America/Lima"));
        StringBuilder b =
                new StringBuilder(
                        head("Invoice", "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"));
        b.append(c("UBLVersionID", "2.1"))
                .append(c("CustomizationID", "2.0"))
                .append(c("ID", id))
                .append(c("IssueDate", date.toLocalDate()))
                .append(c("IssueTime", date.toLocalTime().withNano(0)))
                .append("<cbc:InvoiceTypeCode listID='0101'>03</cbc:InvoiceTypeCode>")
                .append(c("DocumentCurrencyCode", "PEN"))
                .append(signature())
                .append(supplier(false));
        b.append(
                        "<cac:AccountingCustomerParty><cac:Party><cac:PartyIdentification><cbc:ID"
                            + " schemeID='0'>-</cbc:ID></cac:PartyIdentification><cac:PartyLegalEntity>")
                .append(c("RegistrationName", customer))
                .append("</cac:PartyLegalEntity></cac:Party></cac:AccountingCustomerParty>")
                .append(tax(total))
                .append("<cac:LegalMonetaryTotal>")
                .append(amount("LineExtensionAmount", net(total)))
                .append(amount("TaxInclusiveAmount", total))
                .append(amount("PayableAmount", total))
                .append("</cac:LegalMonetaryTotal>");
        BigDecimal assigned = BigDecimal.ZERO;
        int i = 0;
        for (var line : lines) {
            BigDecimal price = (BigDecimal) line.get("amount");
            BigDecimal base = ++i == lines.size() ? net(total).subtract(assigned) : net(price);
            assigned = assigned.add(base);
            BigDecimal tax = price.subtract(base);
            b.append("<cac:InvoiceLine>")
                    .append(c("ID", i))
                    .append("<cbc:InvoicedQuantity unitCode='NIU'>1</cbc:InvoicedQuantity>")
                    .append(amount("LineExtensionAmount", base))
                    .append("<cac:PricingReference><cac:AlternativeConditionPrice>")
                    .append(amount("PriceAmount", price))
                    .append(c("PriceTypeCode", "01"))
                    .append("</cac:AlternativeConditionPrice></cac:PricingReference><cac:TaxTotal>")
                    .append(amount("TaxAmount", tax))
                    .append("<cac:TaxSubtotal>")
                    .append(amount("TaxableAmount", base))
                    .append(amount("TaxAmount", tax))
                    .append("<cac:TaxCategory>")
                    .append(c("Percent", cfg.rate.multiply(new BigDecimal("100"))))
                    .append(c("TaxExemptionReasonCode", cfg.rate.signum() == 0 ? "20" : "10"))
                    .append(scheme())
                    .append("</cac:TaxCategory></cac:TaxSubtotal></cac:TaxTotal><cac:Item>")
                    .append(c("Description", line.get("concept")))
                    .append("</cac:Item><cac:Price>")
                    .append(amount("PriceAmount", base))
                    .append("</cac:Price></cac:InvoiceLine>");
        }
        b.append("</Invoice>");
        return sign(b.toString());
    }

    public byte[] summary(String summaryId, String invoiceId, Instant issued, BigDecimal total) {
        var date = issued.atZone(ZoneId.of("America/Lima")).toLocalDate();
        String xml =
                head(
                                "SummaryDocuments",
                                "urn:sunat:names:specification:ubl:peru:schema:xsd:SummaryDocuments-1")
                        + c("UBLVersionID", "2.0")
                        + c("CustomizationID", "1.1")
                        + c("ID", summaryId)
                        + c("ReferenceDate", date)
                        + c("IssueDate", LocalDate.now(ZoneId.of("America/Lima")))
                        + signature()
                        + supplier(true)
                        + "<sac:SummaryDocumentsLine"
                        + " xmlns:sac='urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1'>"
                        + c("LineID", "1")
                        + c("DocumentTypeCode", "03")
                        + c("ID", invoiceId)
                        + "<cac:Status>"
                        + c("ConditionCode", "1")
                        + "</cac:Status><sac:TotalAmount currencyID='PEN'>"
                        + total
                        + "</sac:TotalAmount><sac:BillingPayment>"
                        + amount("PaidAmount", net(total))
                        + c("InstructionID", cfg.rate.signum() == 0 ? "02" : "01")
                        + "</sac:BillingPayment>"
                        + tax(total)
                        + "</sac:SummaryDocumentsLine></SummaryDocuments>";
        return sign(xml);
    }

    public byte[] sign(String xml) {
        try {
            Document doc = XmlSupport.parse(xml.getBytes(StandardCharsets.UTF_8));
            var factory = XMLSignatureFactory.getInstance("DOM");
            var ref =
                    factory.newReference(
                            "",
                            factory.newDigestMethod(DigestMethod.SHA256, null),
                            List.of(
                                    factory.newTransform(
                                            Transform.ENVELOPED, (TransformParameterSpec) null)),
                            null,
                            null);
            var info =
                    factory.newSignedInfo(
                            factory.newCanonicalizationMethod(
                                    CanonicalizationMethod.INCLUSIVE,
                                    (C14NMethodParameterSpec) null),
                            factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                            List.of(ref));
            KeyStore.PrivateKeyEntry key = cfg.signingKey();
            var ki = factory.getKeyInfoFactory();
            var keyInfo = ki.newKeyInfo(List.of(ki.newX509Data(List.of(key.getCertificate()))));
            var context =
                    new DOMSignContext(
                            key.getPrivateKey(),
                            doc.getElementsByTagNameNS(EXT, "ExtensionContent").item(0));
            context.setDefaultNamespacePrefix("ds");
            factory.newXMLSignature(info, keyInfo, null, "SignatureSP", null).sign(context);
            return XmlSupport.bytes(doc);
        } catch (Exception e) {
            throw new IllegalStateException("XML signing failed", e);
        }
    }
}
