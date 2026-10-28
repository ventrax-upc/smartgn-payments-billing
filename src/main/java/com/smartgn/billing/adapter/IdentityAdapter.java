package com.smartgn.billing.adapter;

import com.smartgn.billing.application.IdentityPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Component
public class IdentityAdapter implements IdentityPort {
    private final RestClient http;

    public IdentityAdapter(
            @Value("${smartgn.iam-url}") String url,
            @Value("${smartgn.internal-token}") String token) {
        var f =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(2))
                                .build());
        f.setReadTimeout(java.time.Duration.ofSeconds(3));
        http =
                RestClient.builder()
                        .baseUrl(url)
                        .requestFactory(f)
                        .defaultHeader("Authorization", "Bearer " + token)
                        .build();
    }

    public Account resolve(String token) {
        try {
            var m =
                    http.post()
                            .uri("/internal/v1/sessions/introspect")
                            .body(Map.of("token", token))
                            .retrieve()
                            .body(Map.class);
            return new Account(
                    java.util.UUID.fromString(m.get("accountId").toString()),
                    m.get("email").toString(),
                    m.get("role").toString(),
                    m.get("accountKind").toString(),
                    m.get("status").toString());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IAM unavailable");
        }
    }
}
