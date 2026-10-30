package com.ventrax.payments.configuration;

public class TraceHttp implements org.springframework.http.client.ClientHttpRequestInterceptor {
    public org.springframework.http.client.ClientHttpResponse intercept(
            org.springframework.http.HttpRequest r,
            byte[] body,
            org.springframework.http.client.ClientHttpRequestExecution execution)
            throws java.io.IOException {
        r.getHeaders().set("X-Correlation-ID", Trace.correlation().toString());
        return execution.execute(r, body);
    }
}
