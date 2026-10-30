package com.ventrax.payments.adapter;

import com.ventrax.payments.application.TaxGateway;

import java.nio.charset.StandardCharsets;

public class LocalTaxGateway implements TaxGateway {
    public Receipt submit(String name, byte[] zip) {
        XmlSupport.parse(XmlSupport.unzipXml(zip));
        return new Receipt("SUBMITTED", "local:" + name, "Local test double submission", null);
    }

    public Receipt poll(String ticket) {
        String xml =
                "<ApplicationResponse"
                    + " xmlns='urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2'"
                    + " xmlns:cbc='"
                        + FiscalXml.CBC
                        + "' xmlns:cac='"
                        + FiscalXml.CAC
                        + "'><cac:DocumentResponse><cac:Response><cbc:ReferenceID>"
                        + XmlSupport.escape(ticket.substring(6))
                        + "</cbc:ReferenceID><cbc:ResponseCode>0</cbc:ResponseCode><cbc:Description>LOCAL"
                        + " TEST DOUBLE - NOT A SUNAT"
                        + " RESPONSE</cbc:Description></cac:Response></cac:DocumentResponse></ApplicationResponse>";
        return new Receipt(
                "LOCAL_ACCEPTED",
                ticket,
                "Local double accepted; no SUNAT call",
                XmlSupport.zip("R-test.xml", xml.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean testMode() {
        return true;
    }
}
