package com.ventrax.payments.adapter;

import com.ventrax.payments.application.TaxGateway;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;

public class SunatBetaGateway implements TaxGateway {
    private final String endpoint, user, password;
    private final HttpClient http;

    public SunatBetaGateway(String endpoint, String user, String password) {
        this.endpoint = endpoint;
        this.user = user;
        this.password = password;
        URI uri = URI.create(endpoint);
        if (!(uri.getScheme().equals("https") && uri.getHost().equals("e-beta.sunat.gob.pe"))
                && !(uri.getScheme().equals("http")
                        && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost())))
            throw new IllegalArgumentException(
                    "SUNAT beta adapter only accepts beta or loopback test endpoints");
        http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    private org.w3c.dom.Document request(String body) {
        String xml =
                "<soapenv:Envelope xmlns:soapenv='http://schemas.xmlsoap.org/soap/envelope/'"
                    + " xmlns:ser='http://service.sunat.gob.pe'"
                    + " xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'><soapenv:Header><wsse:Security><wsse:UsernameToken><wsse:Username>"
                        + XmlSupport.escape(user)
                        + "</wsse:Username><wsse:Password>"
                        + XmlSupport.escape(password)
                        + "</wsse:Password></wsse:UsernameToken></wsse:Security></soapenv:Header><soapenv:Body>"
                        + body
                        + "</soapenv:Body></soapenv:Envelope>";
        try {
            var response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(endpoint))
                                    .timeout(Duration.ofSeconds(15))
                                    .header("Content-Type", "text/xml; charset=utf-8")
                                    .header("SOAPAction", "")
                                    .POST(HttpRequest.BodyPublishers.ofString(xml))
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            try (var in = response.body()) {
                byte[] bytes = in.readNBytes(3_000_001);
                if (bytes.length > 3_000_000)
                    throw new IllegalStateException("SUNAT response too large");
                var doc = XmlSupport.parse(bytes);
                if (response.statusCode() != 200 || XmlSupport.first(doc, "Fault") != null)
                    throw new IllegalStateException("SUNAT transport or SOAP fault");
                return doc;
            }
        } catch (com.ventrax.payments.application.TaxGateway.Rejected e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SUNAT beta response unavailable");
        }
    }

    public Receipt submit(String name, byte[] zip) {
        var doc =
                request(
                        "<ser:sendSummary><fileName>"
                                + XmlSupport.escape(name)
                                + ".zip</fileName><contentFile>"
                                + Base64.getEncoder().encodeToString(zip)
                                + "</contentFile></ser:sendSummary>");
        String ticket = XmlSupport.first(doc, "ticket");
        if (ticket == null || ticket.isBlank() || ticket.length() > 100)
            throw new IllegalStateException("SUNAT ticket missing");
        return new Receipt("SUBMITTED", ticket, "Beta summary submitted", null);
    }

    public Receipt poll(String ticket) {
        var doc =
                request(
                        "<ser:getStatus><ticket>"
                                + XmlSupport.escape(ticket)
                                + "</ticket></ser:getStatus>");
        String status = XmlSupport.first(doc, "statusCode");
        if ("98".equals(status)) return new Receipt("SUBMITTED", ticket, "Processing", null);
        String content = XmlSupport.first(doc, "content");
        if (!java.util.Set.of("0", "99").contains(status) || content == null)
            throw new IllegalStateException("Invalid SUNAT status");
        byte[] zip = Base64.getMimeDecoder().decode(content);
        var cdr = XmlSupport.parse(XmlSupport.unzipXml(zip));
        String code = XmlSupport.first(cdr, "ResponseCode"),
                description = XmlSupport.first(cdr, "Description");
        if (code == null) throw new IllegalStateException("CDR response code missing");
        boolean observed = cdr.getElementsByTagNameNS("*", "Note").getLength() > 0;
        return new Receipt(
                "0".equals(code) ? (observed ? "TEST_OBSERVED" : "TEST_ACCEPTED") : "TEST_REJECTED",
                ticket,
                code + ": " + description,
                zip);
    }

    public boolean testMode() {
        return true;
    }
}
