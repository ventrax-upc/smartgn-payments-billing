package com.ventrax.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.ventrax.payments.adapter.*;

import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class SunatSoapTest {
    HttpServer server;
    AtomicReference<String> response = new AtomicReference<>();
    AtomicReference<String> request = new AtomicReference<>();
    SunatBetaGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    request.set(
                            new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "text/xml");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        gateway =
                new SunatBetaGateway(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                        "test-user",
                        "test-password");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String soap(String body) {
        return "<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body>"
                + body
                + "</s:Body></s:Envelope>";
    }

    @Test
    void submitIncludesSecurityAndReturnsTicket() {
        response.set(soap("<sendSummaryResponse><ticket>ticket-1</ticket></sendSummaryResponse>"));
        var r = gateway.submit("summary", XmlSupport.zip("summary.xml", "<summary/>".getBytes()));
        assertEquals("ticket-1", r.ticket());
        assertTrue(request.get().contains("UsernameToken"));
        assertTrue(request.get().contains("summary.zip"));
    }

    @Test
    void pendingResponseDoesNotBecomeAccepted() {
        response.set(
                soap(
                        "<getStatusResponse><status><statusCode>98</statusCode></status></getStatusResponse>"));
        assertEquals("SUBMITTED", gateway.poll("ticket").status());
    }

    @Test
    void acceptanceAndRejectionPreserveCdr() {
        for (String code : List.of("0", "2001")) {
            byte[] cdr =
                    XmlSupport.zip(
                            "R-summary.xml",
                            ("<ApplicationResponse><ResponseCode>"
                                            + code
                                            + "</ResponseCode><Description>Test</Description></ApplicationResponse>")
                                    .getBytes());
            response.set(
                    soap(
                            "<getStatusResponse><status><statusCode>0</statusCode><content>"
                                    + Base64.getEncoder().encodeToString(cdr)
                                    + "</content></status></getStatusResponse>"));
            var r = gateway.poll("ticket");
            assertEquals(code.equals("0") ? "TEST_ACCEPTED" : "TEST_REJECTED", r.status());
            assertArrayEquals(cdr, r.cdr());
        }
    }

    @Test
    void faultAndMissingCdrRemainUnavailable() {
        response.set(
                soap(
                        "<s:Fault"
                            + " xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><faultstring>Invalid</faultstring></s:Fault>"));
        assertThrows(IllegalStateException.class, () -> gateway.poll("ticket"));
        response.set(soap("<getStatusResponse><statusCode>0</statusCode></getStatusResponse>"));
        assertThrows(IllegalStateException.class, () -> gateway.poll("ticket"));
    }
}
