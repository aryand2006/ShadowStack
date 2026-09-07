-- Enterprise multi-tenancy: organizations, users, and org_id on core tables.

CREATE TABLE IF NOT EXISTS ss_organizations (
  id UUID PRIMARY KEY,
  name VARCHAR(256) NOT NULL UNIQUE,
  status VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ss_users (
  id UUID PRIMARY KEY,
  org_id UUID REFERENCES ss_organizations(id),
  username VARCHAR(128) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  roles VARCHAR(256) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ss_users_org ON ss_users(org_id);

-- Nullable for backfill of existing rows
ALTER TABLE ss_projects ADD COLUMN IF NOT EXISTS org_id UUID;
ALTER TABLE ss_patches ADD COLUMN IF NOT EXISTS org_id UUID;
ALTER TABLE ss_jobs ADD COLUMN IF NOT EXISTS org_id UUID;

CREATE INDEX IF NOT EXISTS idx_ss_projects_org ON ss_projects(org_id);
CREATE INDEX IF NOT EXISTS idx_ss_patches_org ON ss_patches(org_id);
CREATE INDEX IF NOT EXISTS idx_ss_jobs_org ON ss_jobs(org_id);

-- Seed default organization (fixed UUID used by TenantFilter / JWT default claim)
INSERT INTO ss_organizations (id, name, status, created_at, updated_at)
VALUES (
  '00000000-0000-4000-8000-000000000001',
  'default',
  'ACTIVE',
  NOW(),
  NOW()
)
ON CONFLICT (id) DO NOTHING;

-- Backfill existing rows to default org when still null
UPDATE ss_projects SET org_id = '00000000-0000-4000-8000-000000000001' WHERE org_id IS NULL;
UPDATE ss_patches SET org_id = '00000000-0000-4000-8000-000000000001' WHERE org_id IS NULL;
UPDATE ss_jobs SET org_id = '00000000-0000-4000-8000-000000000001' WHERE org_id IS NULL;

-- audit_log from V2: add org_id if the table exists and column is missing
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = current_schema() AND table_name = 'audit_log'
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'audit_log'
      AND column_name = 'org_id'
  ) THEN
    ALTER TABLE audit_log ADD COLUMN org_id UUID;
    CREATE INDEX IF NOT EXISTS idx_audit_log_org ON audit_log(org_id);
  END IF;
END $$;
