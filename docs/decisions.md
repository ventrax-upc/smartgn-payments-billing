# Architecture decisions

The report defines the domain; updated IAM and Installation code defines integration payloads. USER/PRINCIPAL and SUPERADMIN replace historical role names; Billing owns plans rather than a JWT claim. References to other contexts are UUID values, not cross-database foreign keys.

Initial orders allow a null account until IAM provisions it. Financial confirmation and downstream provisioning are separate states: payment is retained while IAM/Installation retry. IAM and Installation receive stable event IDs through their existing private HTTP inputs. An initial order belongs to one provisional installation. Same idempotency key with different content is rejected.

Prices are final PEN amounts configured through environment variables: device 200, installation 50, Pro monthly 35, technical reference 20. Technical final cost is supplied by Support, never inferred from the base price. No price administration API or refunds are included. Calendar periods use America/Lima and an exclusive ending instant.

The local payment adapter is explicitly a test double, not Niubiz sandbox. Outside local mode verification fails unavailable. Fiscal documents remain PENDING_CONFIGURATION rather than claiming SUNAT acceptance when no fiscal configuration exists. Production merchant integration requires the merchant-enabled specification and credentials.
