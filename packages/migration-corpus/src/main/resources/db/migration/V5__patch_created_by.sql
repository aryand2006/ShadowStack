-- Separation of duties: track patch author for review self-approval checks.

ALTER TABLE ss_patches
  ADD COLUMN IF NOT EXISTS created_by VARCHAR(256);
