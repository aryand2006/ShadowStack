-- Compatible with PostgreSQL (prod/docker). Demo profile stays in-memory.

CREATE TABLE IF NOT EXISTS ss_projects (
  id UUID PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  description TEXT,
  repository_url VARCHAR(1024),
  branch VARCHAR(128),
  source_language VARCHAR(64) NOT NULL,
  target_language_version VARCHAR(64),
  status VARCHAR(64) NOT NULL,
  root_path VARCHAR(2048) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS ss_patches (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES ss_projects(id) ON DELETE CASCADE,
  candidate_id UUID,
  rule_name VARCHAR(256) NOT NULL,
  rule_category VARCHAR(128),
  status VARCHAR(64) NOT NULL,
  file_path VARCHAR(1024) NOT NULL,
  start_line INT,
  end_line INT,
  unified_diff TEXT,
  rationale TEXT,
  risk_score DOUBLE PRECISION,
  risk_tier VARCHAR(32),
  confidence DOUBLE PRECISION,
  verification_json TEXT,
  review_json TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ss_patches_project ON ss_patches(project_id);
CREATE INDEX IF NOT EXISTS idx_ss_patches_status ON ss_patches(status);

CREATE TABLE IF NOT EXISTS ss_jobs (
  id UUID PRIMARY KEY,
  project_id UUID,
  patch_id UUID,
  job_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL, -- PENDING, RUNNING, SUCCEEDED, FAILED
  payload_json TEXT,
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  claimed_at TIMESTAMPTZ,
  claimed_by VARCHAR(128)
);
CREATE INDEX IF NOT EXISTS idx_ss_jobs_status ON ss_jobs(status);
