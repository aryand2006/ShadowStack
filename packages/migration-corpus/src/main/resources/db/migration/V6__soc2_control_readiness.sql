-- SOC 2 control readiness: append-only audit_log grants (when role exists),
-- retention-friendly indexes, and control evidence registry.
--
-- CLAIM LANGUAGE: This supports "SOC 2 control readiness / audit-ready controls".
-- It does NOT make ShadowStack SOC 2 certified.

-- ---------------------------------------------------------------------------
-- Soft-delete column for retention without hard DELETE (append-friendly)
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- Retention-friendly indexes (cutoff queries + org-scoped retention)
CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp_active
    ON audit_log (timestamp)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_audit_log_org_timestamp
    ON audit_log (org_id, timestamp);

CREATE INDEX IF NOT EXISTS idx_audit_log_deleted_at
    ON audit_log (deleted_at)
    WHERE deleted_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Append-only grants for application role `shadowstack` (if present)
--
-- DOCUMENTED BEHAVIOR:
--   * REVOKE DELETE  — application must not hard-delete audit rows.
--   * UPDATE remains so RetentionCleanupJob can set deleted_at (soft-delete).
--   * Stricter prod option (DBA): also REVOKE UPDATE and run cleanup as a
--     privileged retention role, e.g.:
--       CREATE ROLE shadowstack_retention LOGIN ...;
--       GRANT SELECT, UPDATE, DELETE ON audit_log TO shadowstack_retention;
--
-- Safe when role is missing (local/CI/demo Postgres without named role).
-- ---------------------------------------------------------------------------
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shadowstack') THEN
    -- App role: insert + select + soft-delete update; no hard delete
    EXECUTE 'GRANT SELECT, INSERT, UPDATE ON audit_log TO shadowstack';
    EXECUTE 'REVOKE DELETE ON audit_log FROM shadowstack';
    RAISE NOTICE 'V6: REVOKE DELETE ON audit_log FROM shadowstack (append-only; soft-delete via UPDATE deleted_at)';
  ELSE
    RAISE NOTICE 'V6: role shadowstack not found — skip audit_log REVOKE (document for prod)';
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Control evidence registry (SOC 2 evidence catalog pointers)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ss_control_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    control_id VARCHAR(64) NOT NULL,
    evidence_type VARCHAR(128) NOT NULL,
    path_or_hash VARCHAR(2048) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    org_id UUID
);

CREATE INDEX IF NOT EXISTS idx_ss_control_evidence_control
    ON ss_control_evidence (control_id);

CREATE INDEX IF NOT EXISTS idx_ss_control_evidence_org
    ON ss_control_evidence (org_id);

CREATE INDEX IF NOT EXISTS idx_ss_control_evidence_created
    ON ss_control_evidence (created_at);

-- Seed static evidence pointers (default org) for control readiness catalog
INSERT INTO ss_control_evidence (id, control_id, evidence_type, path_or_hash, created_at, org_id)
VALUES
  ('a1000000-0000-4000-8000-000000000001', 'CC2.1', 'CODE',
   'packages/migration-corpus/src/main/java/com/shadowstack/corpus/AuditService.java', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-000000000002', 'CC2.1', 'SCHEMA',
   'packages/migration-corpus/src/main/resources/db/migration/V2__create_audit_log.sql', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-000000000003', 'CC2.1', 'SCHEMA',
   'packages/migration-corpus/src/main/resources/db/migration/V6__soc2_control_readiness.sql', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-000000000004', 'CC5.1', 'CODE',
   'apps/api/src/main/java/com/shadowstack/api/security/SecurityConfig.java', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-000000000005', 'CC5.1', 'CODE',
   'apps/api/src/main/java/com/shadowstack/api/security/RBACConfig.java', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-000000000006', 'CC6.3', 'INFRA',
   'infra/k8s/networkpolicy.yaml', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-000000000007', 'CC9.2', 'CODE',
   'apps/api/src/main/java/com/shadowstack/api/jobs/RetentionCleanupJob.java', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-000000000008', 'CC9.2', 'CONFIG',
   'apps/api/src/main/resources/application.yml#shadowstack.retention', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-000000000009', 'CC9.3', 'CI',
   '.github/workflows/ci.yml#security-scan', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-00000000000a', 'CC1.1', 'CODE',
   'apps/api/src/main/java/com/shadowstack/api/service/ReviewService.java', NOW(),
   '00000000-0000-4000-8000-000000000001'),
  ('a1000000-0000-4000-8000-00000000000b', 'DOC', 'DOC',
   'docs/soc2-controls.md', NOW(),
   '00000000-0000-4000-8000-000000000001')
ON CONFLICT (id) DO NOTHING;

-- Grants for evidence table when app role exists
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shadowstack') THEN
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ss_control_evidence TO shadowstack';
  END IF;
END $$;
