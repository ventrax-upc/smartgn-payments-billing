package com.smartgn.billing.configuration;

import com.smartgn.billing.application.IdentityPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain chain(
            HttpSecurity http,
            IdentityPort identities,
            @Value("${smartgn.internal-token}") String internal,
            @Value("${smartgn.jwt-secret}") String secret,
            @Value("${smartgn.allowed-origin}") String origin)
            throws Exception {
        if (internal.length() < 32)
            throw new IllegalArgumentException("Strong service credential required");
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "Idempotency-Key",
                        "X-Checkout-Secret",
                        "X-Correlation-ID"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return http.csrf(c -> c.disable())
                .cors(c -> c.configurationSource(source))
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c.anyRequest().permitAll())
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            protected void doFilterInternal(
                                    jakarta.servlet.http.HttpServletRequest req,
                                    jakarta.servlet.http.HttpServletResponse res,
                                    jakarta.servlet.FilterChain next)
                                    throws java.io.IOException, jakarta.servlet.ServletException {
                                String path = req.getRequestURI();
                                if (path.startsWith("/swagger")
                                        || path.startsWith("/v3/api-docs")
                                        || path.equals("/actuator/health")
                                        || req.getMethod().equals("OPTIONS")) {
                                    next.doFilter(req, res);
                                    return;
                                }
                                String auth = req.getHeader("Authorization");
                                if (path.startsWith("/internal/")) {
                                    if (auth == null
                                            || !java.security.MessageDigest.isEqual(
                                                    ("Bearer " + internal)
                                                            .getBytes(StandardCharsets.UTF_8),
                                                    auth.getBytes(StandardCharsets.UTF_8))) {
                                        res.sendError(401);
                                        return;
                                    }
                                } else if (!path.startsWith("/api/v1/checkout/")) {
                                    if (auth == null || !auth.startsWith("Bearer ")) {
                                        res.sendError(401);
                                        return;
                                    }
                                    try {
                                        var claims =
                                                io.jsonwebtoken.Jwts.parser()
                                                        .verifyWith(
                                                                io.jsonwebtoken.security.Keys
                                                                        .hmacShaKeyFor(
                                                                                secret.getBytes(
                                                                                        StandardCharsets
                                                                                                .UTF_8)))
                                                        .requireIssuer("smartgn-iam-service")
                                                        .build()
                                                        .parseSignedClaims(auth.substring(7))
                                                        .getPayload();
                                        if (claims.getAudience() == null
                                                || !claims.getAudience().contains("smartgn-api")
                                                || claims.getExpiration() == null
                                                || !Objects.equals(
                                                        claims.getSubject(),
                                                        claims.get("accountId", String.class))) {
                                            res.sendError(401);
                                            return;
                                        }
                                        var a = identities.resolve(auth.substring(7));
                                        if (a == null
                                                || !a.accountId()
                                                        .toString()
                                                        .equals(claims.getSubject())
                                                || !a.status().equals("ACTIVE")) {
                                            res.sendError(401);
                                            return;
                                        }
                                        req.setAttribute("actor", a);
                                    } catch (
                                            org.springframework.web.server.ResponseStatusException
                                                    e) {
                                        res.sendError(e.getStatusCode().value());
                                        return;
                                    } catch (Exception e) {
                                        res.sendError(401);
                                        return;
                                    }
                                }
                                next.doFilter(req, res);
                            }
                        },
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
