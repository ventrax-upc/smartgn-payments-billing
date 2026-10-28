CREATE TABLE payment_orders (
 id UUID PRIMARY KEY, account_id UUID, installation_id UUID UNIQUE, reason VARCHAR(30) NOT NULL,
 amount NUMERIC(12,2) NOT NULL CHECK(amount>=0), currency VARCHAR(3) NOT NULL DEFAULT 'PEN',
 status VARCHAR(30) NOT NULL DEFAULT 'PENDING', email VARCHAR(254), first_name VARCHAR(100), last_name VARCHAR(100),
 checkout_hash VARCHAR(64), idempotency_key VARCHAR(200) UNIQUE NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), confirmed_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE order_lines(id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES payment_orders(id), concept VARCHAR(50) NOT NULL, amount NUMERIC(12,2) NOT NULL);
CREATE TABLE payment_transactions(id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES payment_orders(id), provider_reference VARCHAR(200) UNIQUE NOT NULL, status VARCHAR(30) NOT NULL, amount NUMERIC(12,2) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE payment_methods(id UUID PRIMARY KEY, account_id UUID NOT NULL, protected_token TEXT NOT NULL, token_hash VARCHAR(64) UNIQUE NOT NULL, brand VARCHAR(40) NOT NULL, last_four VARCHAR(4) NOT NULL, active BOOLEAN NOT NULL DEFAULT true, is_default BOOLEAN NOT NULL DEFAULT false);
CREATE UNIQUE INDEX one_default_card ON payment_methods(account_id) WHERE active AND is_default;
CREATE TABLE subscriptions(account_id UUID PRIMARY KEY, installed_at TIMESTAMPTZ NOT NULL, method_id UUID REFERENCES payment_methods(id), auto_renew BOOLEAN NOT NULL DEFAULT false);
CREATE TABLE subscription_periods(id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES subscriptions(account_id), order_id UUID UNIQUE NOT NULL REFERENCES payment_orders(id), starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL CHECK(ends_at>starts_at));
CREATE TABLE coverage_decisions(visit_id UUID PRIMARY KEY, account_id UUID NOT NULL, meter_id UUID NOT NULL, completed_at TIMESTAMPTZ NOT NULL, cost NUMERIC(12,2) NOT NULL CHECK(cost>=0), covered BOOLEAN NOT NULL, balance NUMERIC(12,2) NOT NULL, order_id UUID UNIQUE REFERENCES payment_orders(id));
CREATE TABLE billing_documents(id UUID PRIMARY KEY, order_id UUID UNIQUE NOT NULL REFERENCES payment_orders(id), series VARCHAR(4) NOT NULL, number BIGINT NOT NULL, fiscal_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_CONFIGURATION', xml TEXT, response TEXT, UNIQUE(series,number));
CREATE SEQUENCE fiscal_number;
CREATE TABLE integration_inbox(event_id UUID PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE outbox(id UUID PRIMARY KEY, event_type VARCHAR(80) NOT NULL, aggregate_id UUID NOT NULL, payload TEXT NOT NULL, correlation_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), delivered_at TIMESTAMPTZ, account_id UUID, installation_delivered BOOLEAN NOT NULL DEFAULT false, attempts INT NOT NULL DEFAULT 0, available_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE billing_audit(id UUID PRIMARY KEY, actor_id UUID, action VARCHAR(100) NOT NULL, resource_id UUID NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now());
