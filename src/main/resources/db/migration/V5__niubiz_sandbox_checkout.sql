CREATE SEQUENCE niubiz_sandbox_purchase_number START WITH 100000000000 MAXVALUE 999999999999;
CREATE TABLE niubiz_sandbox_checkouts (
    order_id uuid PRIMARY KEY REFERENCES payment_orders(id),
    purchase_number bigint NOT NULL UNIQUE DEFAULT nextval('niubiz_sandbox_purchase_number'),
    expires_at timestamptz NOT NULL,
    token_hash text UNIQUE
);
