CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE migration_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id VARCHAR(128) NOT NULL,
    rule_name VARCHAR(256) NOT NULL,
    source_language VARCHAR(64) NOT NULL,
    source_version VARCHAR(32) NOT NULL,
    target_language VARCHAR(64) NOT NULL,
    target_version VARCHAR(32) NOT NULL,
    before_snippet TEXT NOT NULL,
    after_snippet TEXT NOT NULL,
    ast_context JSONB,
    libraries_involved JSONB,
    risk_tier VARCHAR(32) NOT NULL,
    confidence_score DOUBLE PRECISION NOT NULL,
    verification_passed BOOLEAN NOT NULL,
    verification_metrics JSONB,
    developer_accepted BOOLEAN,
    rejection_reason TEXT,
    time_to_accept_ms BIGINT,
    embedding vector(768),
    before_ast_hash VARCHAR(128),
    after_ast_hash VARCHAR(128),
    project_id VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_migration_entries_rule ON migration_entries(rule_id);
CREATE INDEX idx_migration_entries_acceptance ON migration_entries(developer_accepted);
CREATE INDEX idx_migration_entries_risk ON migration_entries(risk_tier);
CREATE INDEX idx_migration_entries_embedding ON migration_entries USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX idx_migration_entries_created ON migration_entries(created_at);
