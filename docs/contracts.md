# REST and integration contracts

Runtime `/v3/api-docs` and exported `openapi.json` define payload validation and authentication. Current IAM JWTs identify accounts; Billing additionally introspects sessions and enforces principal ownership. All financial reads and writes deny delegated users. The internal service credential is independent from user JWTs.

| Method | Path | Behavior |
| --- | --- | --- |
| POST | /api/v1/checkout/orders | Server-priced initial purchase; Idempotency-Key and X-Checkout-Secret; validates provisional Installation contact |
| GET | /api/v1/checkout/orders/{id} | Secret-protected initial-order status, without private contact/token fields |
| POST | /api/v1/checkout/orders/{id}/sessions | Explicit local checkout instructions; never a claimed Niubiz hosted session |
| POST/GET | /api/v1/payment-methods | Verify provider token ownership, store encrypted token and masked metadata; list only owner's masked methods |
| PUT | /api/v1/payment-methods/{id}/default | Atomically select an owned active method |
| DELETE | /api/v1/payment-methods/{id} | Unlink owned method and disable dependent auto-renew; unresolved debits must be reconciled first |
| POST | /api/v1/subscriptions/pro | Request a monthly Pro payment with provider-verified method and optional recurring consent |
| GET | /api/v1/subscriptions/me | Current effective plan and last applicable paid ending time |
| PATCH | /api/v1/subscriptions/me/renewal | Set autoRenew; enabling requires an active method with provider recurring consent |
| GET | /api/v1/technical-statements/{id} | Frozen completed-visit cost, coverage, balance and payable order reference |
| GET | /api/v1/payments/orders/{id} | Owner-only order status |
| POST | /api/v1/payments/orders/{id}/pay | Start one durable payment operation for an owned order/method |
| POST | /api/v1/payments/orders/{id}/retry | Explicit retry of a verified decline, with Idempotency-Key; never a second debit for UNKNOWN |
| GET | /api/v1/payments/history | Isolated orders, line amounts, attempts and document references; offset/limit and optional reason/status filters |
| GET | /api/v1/billing-documents/{id} | Own fiscal metadata and response; payment status remains separate |
| GET | /api/v1/billing-documents/{id}/download | format=pdf, xml or cdr; unavailable artifacts return conflict |

The initial product is the single SmartGN device plus installation bundle; its concepts and prices are calculated on the backend. No browser-supplied amount is trusted. The caller must generate a cryptographically random checkout secret and retain it privately; only its hash is stored.

## Internal contracts

- POST `/internal/v1/payments/confirmations`: orderId and providerReference. Billing re-verifies the referenced provider evidence, amount and order; `confirmed:true` supplied by a browser is not proof.
- GET `/internal/v1/accounts/{id}/entitlements`: accountId, proActive, plan and periodEndsAt. Reads only Billing data and does not call IAM, preventing authorization callback loops.
- POST `/internal/v1/events`: envelope event_id,event_type,aggregate_id,correlation_id,payload. Installation's existing publisher may encode payload as a JSON string; the receiver also accepts a JSON object.
- POST `/internal/v1/technical-visits/completions`: controlled Support bridge with eventId,visitId,accountId,meterId,completedAt,cost. The main event is TechnicalVisitCompleted; the bridge shares its deduplication and coverage path.
- Local-only POST `/internal/v1/local-provider/cards`: providerToken,accountId,brand,lastFour,recurringAuthorized,nextStatus. Establishes controlled provider ownership evidence.
- Local-only POST `/internal/v1/local-provider/results`: reference,orderId,status,amount. Immutable terminal results; UNKNOWN may be resolved. Both fixture inputs require service authentication and are persisted.

General events use non-secret identifiers. PaymentConfirmed carries order, account (once provisioned), installation, concept, amount, currency and verified date. CoverageCalculated carries visit/account, cost, coverage, balance and order reference. BillingDocumentIssued and BillingDocumentTaxStatusChanged identify fiscal records. MQTT publication on `smartgn/events/payments/#` uses QoS 1; service ACLs permit Installation and Support to publish only in their own namespaces. Consumers commit before manual acknowledgement and quarantine invalid/conflicting events. HTTP onboarding and MQTT publication have independent durable delivery receipts and leases.

A repeated event ID with different business content is a conflict. Repeated visit evidence with a new transport event ID also cannot create a second charge; conflicting visit evidence is rejected. Missing dependencies deny authorization rather than granting access. CORS allows only the configured origin, and checkout POSTs have a bounded per-process/IP rate limit; a multi-replica gateway needs its own shared limit.

## Fiscal states

PENDING_CONFIGURATION means no document has been generated. ISSUED_TEST means XML/PDF exist with a test certificate. SUBMITTED retains the provider ticket. LOCAL_ACCEPTED identifies the local tax double only; TEST_ACCEPTED/TEST_OBSERVED/TEST_REJECTED identify beta results. A transport failure retains retry state and the same signed summary; a SOAP rejection stores its code/description for regularization. No state claims productive SUNAT validity. Production fiscal identity/certificates and merchant Niubiz contracts remain external prerequisites.

CoverageCalculated is delivered durably to Support's `/internal/v1/events` when SUPPORT_BASE_URL is configured. HTTP and MQTT have independent receipts and stable event IDs. Non-financial Support lifecycle events are ignored by Billing.
