# Architecture decisions

The service uses the USER/SUPERADMIN roles and PRINCIPAL/DELEGATED account kinds exposed by IAM. IAM owns credentials, current sessions and grants; Billing owns paid periods and plan decisions. Only UUID references cross context boundaries; runtime SQL never joins another service's database.

Initial orders may lack accountId until IAM provisions the buyer's account. Verified payment, IAM provisioning, Installation enablement, message publication and fiscal submission have independent durable states. There is no distributed ACID transaction or browser-return payment proof. Unique receipts, PostgreSQL locks, leased jobs and immutable provider references prevent duplicate effects.

Final prices are configurable PEN values: device 200, installation 50, monthly Pro 35 and technical reference 20. Order lines freeze the applied prices. Support supplies final visit cost and effective completion; Billing neither trusts SPA-supplied costs nor imposes the reference as a minimum. Periods use America/Lima calendar months and an exclusive end. Renewals start no earlier than an existing paid period's end; late confirmations start at verification time. A failed renewal never extends paid access. Historical coverage snapshots do not change afterward.

The local provider is persistent and available only under the local Spring profile. Card ownership/recurring consent come from provider evidence; encrypted tokens and masked fields remain separate. Uncertain results reconcile the same operation after restarts. Automatic decline retries stop; the owner may explicitly retry a final decline with a new operation key. Non-local merchant operations remain unavailable until authorized Niubiz contracts/credentials exist.

Fiscal state and payment state are separate. Signed UBL 2.1 boletas/PDFs, signed daily summaries, unique series/number, daily counters and SOAP ticket/CDR state are owned by Billing. Missing configuration remains pending; a local acceptance is labeled LOCAL_ACCEPTED, and SUNAT beta acceptance is TEST_ACCEPTED. No local or beta document has productive fiscal validity.

General events contain no passwords, activation links or card tokens. HTTP onboarding and MQTT publication maintain separate delivery receipts. MQTT service ACLs isolate context namespaces; consumers acknowledge after durable processing and quarantine malformed/conflicting inputs. Existing Installation HTTP outbox delivery is a compatible Sprint 1 bridge; real Support's event contract remains provisional until integrated.

Refunds, a price administration API and a SPA are not supported. Deployment configuration targets the local Docker environment.
