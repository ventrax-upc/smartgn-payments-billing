package com.ventrax.payments;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ventrax.payments.application.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@SpringBootTest(
        properties = {
            "spring.profiles.active=local",
            "smartgn.internal-token=internal-service-token-at-least-thirty-two-characters",
            "smartgn.jwt-secret=jwt-signing-secret-at-least-thirty-two-characters",
            "smartgn.card-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "smartgn.iam-url=http://127.0.0.1:1",
            "smartgn.installation-url=http://127.0.0.1:1",
            "smartgn.allowed-origin=http://localhost:4200",
            "smartgn.worker-delay=3600000",
            "smartgn.tax.mode=disabled"
        })
@AutoConfigureMockMvc
class SecurityTest {
    static final String SCHEMA = "security_" + UUID.randomUUID().toString().replace("-", "");
    static final String KEY = "jwt-signing-secret-at-least-thirty-two-characters";
    static final UUID ACCOUNT = UUID.randomUUID();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        r.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Autowired MockMvc mvc;
    @MockitoBean IdentityPort identities;

    String token(String audience, Instant expires) {
        return io.jsonwebtoken.Jwts.builder()
                .issuer("smartgn-iam-service")
                .subject(ACCOUNT.toString())
                .claim("accountId", ACCOUNT.toString())
                .claim("role", "USER")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expires))
                .audience()
                .add(audience)
                .and()
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(KEY.getBytes()))
                .compact();
    }

    String valid() {
        return token("smartgn-api", Instant.now().plusSeconds(60));
    }

    @BeforeEach
    void identity() {
        when(identities.resolve(anyString()))
                .thenReturn(
                        new IdentityPort.Account(
                                ACCOUNT, "test@example.test", "USER", "PRINCIPAL", "ACTIVE"));
    }

    @Test
    void financialRoutesNeedCurrentSession() throws Exception {
        mvc.perform(get("/api/v1/payments/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments/history").header("Authorization", "Bearer " + valid()))
                .andExpect(status().isOk());
    }

    @Test
    void delegatesAreDenied() throws Exception {
        when(identities.resolve(anyString()))
                .thenReturn(
                        new IdentityPort.Account(
                                ACCOUNT, "test@example.test", "USER", "DELEGATED", "ACTIVE"));
        mvc.perform(get("/api/v1/payments/history").header("Authorization", "Bearer " + valid()))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedSessionIsRejected() throws Exception {
        when(identities.resolve(anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(get("/api/v1/payments/history").header("Authorization", "Bearer " + valid()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dependencyOutageDoesNotGrantAccess() throws Exception {
        when(identities.resolve(anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(get("/api/v1/payments/history").header("Authorization", "Bearer " + valid()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void audienceExpiryAndTamperingAreChecked() throws Exception {
        for (String t :
                List.of(
                        token("wrong", Instant.now().plusSeconds(60)),
                        token("smartgn-api", Instant.now().minusSeconds(60)),
                        valid() + "broken"))
            mvc.perform(get("/api/v1/payments/history").header("Authorization", "Bearer " + t))
                    .andExpect(status().isUnauthorized());
    }

    @Test
    void userJwtDoesNotAuthorizeInternalRoutes() throws Exception {
        mvc.perform(
                        get("/internal/v1/accounts/" + ACCOUNT + "/entitlements")
                                .header("Authorization", "Bearer " + valid()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correlationIsReturnedAndInvalidValueRejected() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/actuator/health").header("X-Correlation-ID", id))
                .andExpect(header().string("X-Correlation-ID", id));
        mvc.perform(get("/actuator/health").header("X-Correlation-ID", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidProviderInputCannotConfirmPayment() throws Exception {
        mvc.perform(
                        post("/internal/v1/payments/confirmations")
                                .header(
                                        "Authorization",
                                        "Bearer"
                                            + " internal-service-token-at-least-thirty-two-characters")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkoutCreationIsRateLimited() throws Exception {
        for (int n = 0; n < 30; n++)
            mvc.perform(
                    post("/api/v1/checkout/orders").contentType("application/json").content("{}"));
        mvc.perform(post("/api/v1/checkout/orders").contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void ownerCanReadConfiguredPricesAndCoverageWithoutGrantingPro() throws Exception {
        mvc.perform(get("/api/v1/subscriptions/me").header("Authorization", "Bearer " + valid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.technicalBasePriceReference").value(20.0))
                .andExpect(jsonPath("$.monthlyProPrice").value(35.0))
                .andExpect(jsonPath("$.technicalCoveragePercent").value(0))
                .andExpect(jsonPath("$.currency").value("PEN"));
    }
}
