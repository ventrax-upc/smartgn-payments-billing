# SmartGN Payments and Billing

Sprint 2 service for initial device/installation purchases, tokenized methods, monthly Pro periods, historical technical coverage, post-visit charges and electronic boletas. Java 25, Spring Boot 4.1.1, PostgreSQL/Flyway, authenticated MQTT and separate IAM/Installation contracts. Refunds and price administration UI/API are not supported.

## Run locally

Docker Desktop Linux engine is required. `./scripts/start-local.ps1 -Integrated` generates ignored local secrets and a self-signed DEMO certificate, builds this checkout and sibling `../smartgn-iam` and `../smartgn-installation-device-management`, then starts independent databases and an authenticated broker. It does not reset existing volumes.

| Service | Integrated URL/port |
| --- | --- |
| Billing and Swagger | http://localhost:28083/swagger-ui/index.html |
| IAM | http://localhost:18080 |
| Installation | http://localhost:18081 |
| MQTT | localhost:1885 |
| Development notification fixture | http://localhost:18091 |

The integrated stack uses the Billing deployment above as its single Billing authority. `./scripts/start-local.ps1` is an alternative deployment mode and starts standalone Billing on port 8083, PostgreSQL on 5434 and MQTT on 1884. Configure IAM_BASE_URL, INSTALLATION_BASE_URL, JWT_SECRET and INTERNAL_SERVICE_TOKEN in ignored `.env` to match existing services. Use compatible IAM and Installation checkouts, and point IAM's BILLING_BASE_URL and Installation's event sink to that same Billing deployment. Each environment uses one Billing database/authority. All HTTP listeners bind to loopback. No cloud deployment or public payment checkout is configured.

## Development commands

- `./scripts/test.ps1`: Maven verify in JDK 25 with a fresh disposable PostgreSQL container. It does not require a host JDK or remove application volumes.
- `./scripts/smoke-local.ps1`: acceptance run against the integrated stack. Uses Python standard library, actual IAM/Installation/PostgreSQL/MQTT and labeled provider/completion-event fixtures. Creates uniquely named test accounts and changes only their isolated subscription periods to exercise expiration/renewal.
- `./mvnw.cmd verify`: host JDK 25 build; TEST_DB_URL must point to a dedicated verification database with test credentials documented in scripts/test.ps1.

Source, contracts, errors and API documentation use English. Formatting follows the sibling Installation AOSP Java convention. See [API contracts](docs/contracts.md), [architecture decisions](docs/decisions.md) and the [exported OpenAPI](docs/openapi.json) for service documentation.

## Business configuration

Final PEN amounts default to device 200, installation 50, Pro monthly 35 and technical reference 20, configured through DEVICE_PRICE, INSTALLATION_PRICE, PRO_PRICE and TECHNICAL_BASE_PRICE. Existing orders store their original line amounts. The final technical cost belongs to Support and can differ from the reference price. Tax rate defaults to the explicitly configured demonstration value 0.18; the total paid is not increased by the tax breakdown. Fiscal issuer configuration is separate from commercial prices.

Pro activates only after verified payment. Paid periods use calendar months in America/Lima and an exclusive ending instant. Failed or uncertain renewals do not extend paid access. UNKNOWN operations reconcile against the same provider operation; they never create a second debit. DECLINED operations stop automatic attempts; an explicit owner retry is required. Card removal disables auto-renewal and cannot discard unresolved operations.

## Contracts and local provider

POST `/api/v1/checkout/orders` takes installationId,email,firstName,lastName, an Idempotency-Key and a private caller-generated X-Checkout-Secret (at least 32 random characters). Installation state/contact are checked server-side and the amount is calculated by Billing. Order reads/sessions require the secret; UUID alone is insufficient. Browser returns cannot confirm a payment.

Local provider setup is available only with Spring's `local` profile and a service credential: register `/internal/v1/local-provider/cards` ownership evidence, then submit only its token to `/api/v1/payment-methods`. Provider results can be controlled through `/internal/v1/local-provider/results` as CONFIRMED, DECLINED or UNKNOWN. Both are persisted; PAN/CVV are not accepted. These routes are explicit test fixtures, not Niubiz sandbox. Outside local mode all merchant operations fail unavailable until a merchant-specific Niubiz adapter and credentials are supplied.

Initial payments create durable deliveries to IAM purchase-confirmations and Installation payment-confirmations with stable event IDs. Financial confirmation survives downstream outages. Billing owns periods and `/internal/v1/accounts/{id}/entitlements`; IAM remains authoritative for current identity/session/grants. InstallationCompleted and TechnicalVisitCompleted are consumed idempotently over internal HTTP/MQTT. The Support event contract is provisional until its service is integrated.

See `docs/contracts.md` and runtime `/v3/api-docs` for public/private routes and schemas. Internal routes must never be exposed by the public gateway. Financial APIs require the current principal account; delegates cannot manage financial resources.

## Electronic invoicing

Invoices, signed UBL XML, PDFs, daily summaries and CDR evidence are stored independently of payment state. Local mode produces clearly marked DEMO PDFs and LOCAL_ACCEPTED responses. `SUNAT_MODE=beta` selects the actual SUNAT beta SOAP adapter; test RUC/user/password from the official manual and a local signing certificate are used only for testing. Beta status is TEST_ACCEPTED/TEST_OBSERVED/TEST_REJECTED, never production acceptance. Missing issuer/key configuration retains PENDING_CONFIGURATION. Transport failure retains the exact summary and retries it without allocating another document number; SOAP rejection preserves its response for regularization.

Production merchant/fiscal enablement, real certificates and customer fiscal requirements must be supplied before using this service with real transactions. No production integration is claimed by a local or beta result.
