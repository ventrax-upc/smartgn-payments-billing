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
