# Verification and remaining scope

## Implemented baseline

Independent PostgreSQL schema and Flyway migration; final PEN prices and persisted order lines; provisional checkout; opaque caller-held checkout secret (only hash stored); service-authenticated local provider verification; initial-payment delivery to existing IAM/Installation private contracts; current JWT/session checks; principal-only financial operations; encrypted local card tokens with default/unlink operations; paid Pro periods; historical coverage and technical balances; financial order history; private entitlements; receipt numbering with a pending fiscal state; Docker and OpenAPI.

## Validation

Executed: 11 tests, zero failures/errors/skips, Maven BUILD SUCCESS using JDK 25 container builds. Isolated PostgreSQL 17.6 migration applied successfully. Domain tests cover calendar month boundaries, exclusive period ends and invalid money. PostgreSQL tests cover constraints, fiscal sequence uniqueness, controlled provider verification, delegated financial denial and 100 repeated payment confirmations with one transaction, document and outbox effect.

IAM and Installation companion changes were compiled on Java 25. Their full regression suites and the integrated paid onboarding workflow were not executed. Runtime Billing health and anonymous denials on public financial/private entitlement endpoints were exercised. Runtime OpenAPI was exported.

## Incomplete relative to the approved plan

This is not the complete Sprint 2 delivery. Fiscal UBL generation, XML signature, PDF/XML download, SOAP SUNAT beta and CDR handling remain unimplemented; PENDING_CONFIGURATION is a placeholder that does not establish issuance. MQTT publication/consumption is absent; outbox currently delivers only initial onboarding over HTTP. Renewal scheduling creates an order but does not initiate a recurring provider debit or reconcile uncertain provider outcomes automatically. The local card registration format is a double and does not verify real provider ownership. No Niubiz sandbox calls occurred.

HTTP security regression tests, concurrent-process integration tests, rate limits, full audit coverage and correlation propagation remain to be completed. Financial history currently lists orders; a consolidated history including attempts and fiscal records is pending. No claim of production readiness, fiscal compliance, real card tokenization or completed end-to-end integration is made.

Refunds, frontend, administrative price API and deployment are intentionally excluded.
