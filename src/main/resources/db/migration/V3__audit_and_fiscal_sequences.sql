ALTER TABLE billing_audit ADD COLUMN correlation_id UUID NOT NULL DEFAULT gen_random_uuid(), ADD COLUMN details TEXT NOT NULL DEFAULT '{}';
CREATE TABLE fiscal_daily_sequences(issue_day DATE PRIMARY KEY, value INTEGER NOT NULL CHECK(value BETWEEN 1 AND 99999));
INSERT INTO fiscal_daily_sequences(issue_day,value)
SELECT to_date(parts[1],'YYYYMMDD'),max(parts[2]::integer)
FROM (SELECT regexp_match(name,'-RC-([0-9]{8})-([0-9]+)$') AS parts FROM fiscal_summaries) existing
WHERE parts IS NOT NULL GROUP BY parts[1];
ALTER SEQUENCE fiscal_number MAXVALUE 99999999;
