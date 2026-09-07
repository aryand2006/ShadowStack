CREATE TABLE IF NOT EXISTS ss_rule_prior_calibration (
    rule_name   VARCHAR(255) PRIMARY KEY,
    accepts     BIGINT NOT NULL DEFAULT 0,
    rejects     BIGINT NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
