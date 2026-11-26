# Business and provider workflows

## Business configuration

Final PEN amounts default to device 200, installation 50, Pro monthly 35 and technical reference 20, configured through DEVICE_PRICE, INSTALLATION_PRICE, PRO_PRICE and TECHNICAL_BASE_PRICE. Existing orders store their original line amounts. The final technical cost belongs to Support and can differ from the reference price. Tax rate defaults to the explicitly configured demonstration value 0.18; the total paid is not increased by the tax breakdown. Fiscal issuer configuration is separate from commercial prices.

Pro activates only after verified payment. Paid periods use calendar months in America/Lima and an exclusive ending instant. Failed or uncertain renewals do not extend paid access. UNKNOWN operations reconcile against the same provider operation; they never create a second debit. DECLINED operations stop automatic attempts; an explicit owner retry is required. Card removal disables auto-renewal and cannot discard unresolved operations.

## Contracts and local provider

POST `/api/v1/checkout/orders` takes installationId,email,firstName,lastName, an Idempotency-Key and a private caller-generated X-Checkout-Secret (at least 32 random characters). Installation state/contact are checked server-side and the amount is calculated by Billing. Order reads/sessions require the secret; UUID alone is insufficient. Browser returns cannot confirm a payment.

Local provider setup is available only with Spring's `local` profile and a service credential: register `/internal/v1/local-provider/cards` ownership evidence, then submit only its token to `/api/v1/payment-methods`. Provider results can be controlled through `/internal/v1/local-provider/results` as CONFIRMED, DECLINED or UNKNOWN. Both are persisted; PAN/CVV are not accepted. These routes are explicit test fixtures, not Niubiz sandbox. Outside local mode all merchant operations fail unavailable until a merchant-specific Niubiz adapter and credentials are supplied.

Initial payments create durable deliveries to IAM purchase-confirmations and Installation payment-confirmations with stable event IDs. Financial confirmation survives downstream outages. Billing owns periods and `/internal/v1/accounts/{id}/entitlements`; IAM remains authoritative for current identity/session/grants. InstallationCompleted and TechnicalVisitCompleted are consumed idempotently over internal HTTP/MQTT. Support completion and CoverageCalculated contracts are integrated over authenticated HTTP, with independent optional MQTT delivery receipts.

See `docs/contracts.md` and runtime `/v3/api-docs` for public/private routes and schemas. Internal routes must never be exposed by the public gateway. Financial APIs require the current principal account; delegates cannot manage financial resources.

## Electronic invoicing

Invoices, signed UBL XML, PDFs, daily summaries and CDR evidence are stored independently of payment state. Local mode produces clearly marked DEMO PDFs and LOCAL_ACCEPTED responses. `SUNAT_MODE=beta` selects the actual SUNAT beta SOAP adapter; test RUC/user/password from the official manual and a local signing certificate are used only for testing. Beta status is TEST_ACCEPTED/TEST_OBSERVED/TEST_REJECTED, never production acceptance. Missing issuer/key configuration retains PENDING_CONFIGURATION. Transport failure retains the exact summary and retries it without allocating another document number; SOAP rejection preserves its response for regularization.

Production merchant/fiscal enablement, real certificates and customer fiscal requirements must be supplied before using this service with real transactions. No production integration is claimed by a local or beta result.

## Niubiz Sandbox for initial checkout

Billing supports a separate opt-in Niubiz Sandbox flow under the Spring
`local` profile. Set `NIUBIZ_SANDBOX_ENABLED=true`,
`NIUBIZ_SANDBOX_MERCHANT`, `NIUBIZ_SANDBOX_USER` and
`NIUBIZ_SANDBOX_PASSWORD`. Billing uses `https://apisandbox.vnforappstest.com`
for security, session and authorization. Security uses `GET /api.security/v1/security`.
The sandbox is limited to initial PEN purchases.

The checkout sessions route returns a session key for Niubiz's hosted card
fields. POST the resulting transaction token to the order's
`/sandbox-authorizations` route with the private checkout secret. Billing
reserves a unique purchase number, checks the provider response against the
order amount and currency, and records approved payments through the usual
onboarding and fiscal outbox. Card numbers and CVVs stay in Niubiz's fields.
One authorization is allowed per attempt. A declined order can start a new
session with a fresh purchase number and token. An uncertain result requires
reconciliation before another purchase. This does not enable Niubiz
production, tokenization for saved cards, or recurring billing.

### Browser checkout in the local profile

When `LOCAL_BROWSER_CHECKOUT_ENABLED=true` and Niubiz Sandbox is disabled, the local checkout session advertises a simulator action. It requires the order's checkout secret. Billing derives the reference and amount from its own order, records the simulator result and runs the existing provider verification and onboarding flow. The browser supplies no amount, payment result or payment-provider credentials. Without the flag the protected fixture workflow remains available in the local profile.