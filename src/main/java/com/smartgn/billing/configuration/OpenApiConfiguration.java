package com.smartgn.billing.configuration;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;

import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("SmartGN Payments and Billing")
                                .version("0.1.0")
                                .description(
                                        "Sprint 2 payments, subscriptions, coverage and electronic"
                                            + " invoicing. Local provider fixtures are explicitly"
                                            + " test doubles; fiscal beta results have no"
                                            + " production validity."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT"))
                                .addSecuritySchemes(
                                        "serviceAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .description(
                                                        "Independent internal service"
                                                                + " credential")));
    }

    @Bean
    org.springdoc.core.customizers.OpenApiCustomizer securityContracts() {
        return api -> {
            if (api.getPaths() != null)
                api.getPaths()
                        .forEach(
                                (path, item) ->
                                        item.readOperations()
                                                .forEach(
                                                        op -> {
                                                            if (path.startsWith("/internal/"))
                                                                op.setSecurity(
                                                                        java.util.List.of(
                                                                                new SecurityRequirement()
                                                                                        .addList(
                                                                                                "serviceAuth")));
                                                            else if (!path.startsWith(
                                                                    "/api/v1/checkout/"))
                                                                op.setSecurity(
                                                                        java.util.List.of(
                                                                                new SecurityRequirement()
                                                                                        .addList(
                                                                                                "bearerAuth")));
                                                            else
                                                                op.setSecurity(java.util.List.of());
                                                            if (op.getDescription() == null)
                                                                op.setDescription(
                                                                        path.startsWith(
                                                                                        "/internal/")
                                                                                ? "Internal service"
                                                                                      + " credential"
                                                                                      + " required."
                                                                                      + " Exclude"
                                                                                      + " from"
                                                                                      + " public"
                                                                                      + " gateway"
                                                                                      + " routes."
                                                                                : path.startsWith(
                                                                                                "/api/v1/checkout/")
                                                                                        ? "Anonymous"
                                                                                              + " checkout;"
                                                                                              + " subsequent"
                                                                                              + " order"
                                                                                              + " access"
                                                                                              + " requires"
                                                                                              + " the private"
                                                                                              + " X-Checkout-Secret."
                                                                                        : "Current"
                                                                                              + " IAM session,"
                                                                                              + " principal"
                                                                                              + " account"
                                                                                              + " and ownership"
                                                                                              + " are checked"
                                                                                              + " by Billing.");
                                                        }));
        };
    }
}
