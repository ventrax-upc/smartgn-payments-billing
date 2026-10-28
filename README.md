# SmartGN Payments and Billing

Java 25, Spring Boot 4.1.1 and independent PostgreSQL/Flyway billing context. This implementation is an incremental baseline; see docs/verification.md for implemented and remaining work. No refunds.

## Local execution

Copy .env.example to .env and set database password and the existing IAM/service credentials. Run `./scripts/start-local.ps1`. The local profile uses an explicit payment double. Swagger: http://localhost:8083/swagger-ui/index.html, health: http://localhost:8083/actuator/health.

Use `./scripts/test.ps1` for Maven verification inside JDK 25 with an isolated disposable PostgreSQL database. No existing volumes are removed. For host builds use `./mvnw.cmd verify` with JDK 25 and TEST_DB_URL pointing to a dedicated verification database.

Prices are final PEN amounts configured with DEVICE_PRICE=200, INSTALLATION_PRICE=50, PRO_PRICE=35 and TECHNICAL_BASE_PRICE=20. Restart after a configuration change; existing orders retain their amounts.

## Checkout and local payment verification

Create a provisional request using Installation. POST /api/v1/checkout/orders with installationId,email,firstName,lastName, Idempotency-Key and a caller-generated random X-Checkout-Secret of at least 32 characters. Retain that secret privately for the order GET and session POST. Amounts are computed by Billing.

In local mode only, submit a controlled provider result to POST /internal/v1/local-provider/results with reference,orderId,status (CONFIRMED, DECLINED or UNKNOWN),amount, authenticated with the service credential. POST /internal/v1/payments/confirmations with orderId,providerReference verifies the result against that fixture. This is not a Niubiz sandbox transaction.

On confirmed initial payment a durable worker calls IAM purchase-confirmations and Installation payment-confirmations. Install the companion minimal commits before checkout. Configure Installation SMARTGN_EVENTS_SINK_URL to http://billing:8083/internal/v1/events (or a reachable host address) and SMARTGN_EVENTS_SINK_TOKEN to the Billing service credential to deliver InstallationCompleted.

Set SMARTGN_BILLING_BASE_URL and SMARTGN_BILLING_SERVICE_TOKEN on IAM to enable the real private entitlement endpoint. No plan is trusted from JWT claims. Public financial routes require a current USER/PRINCIPAL session.

## External boundaries

Niubiz merchant HTTP integration and fiscal transmission are not active. SUNAT documents remain PENDING_CONFIGURATION. Local card tokens must begin test_ and are encrypted in storage. Never use real payment card details with this fixture. Real credentials and customer data must not be committed.
