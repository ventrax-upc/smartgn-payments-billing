CREATE TABLE niubiz_sandbox_used_tokens (
    token_hash text PRIMARY KEY
);

INSERT INTO niubiz_sandbox_used_tokens (token_hash)
SELECT token_hash FROM niubiz_sandbox_checkouts WHERE token_hash IS NOT NULL;
