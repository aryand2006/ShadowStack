CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(256) NOT NULL,
    source_language VARCHAR(64) NOT NULL,
    source_version VARCHAR(32) NOT NULL,
    target_language VARCHAR(64) NOT NULL,
    target_version VARCHAR(32) NOT NULL,
    repo_url VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    config JSONB
);

CREATE INDEX idx_projects_name ON projects(name);
CREATE INDEX idx_projects_languages ON projects(source_language, target_language);

-- Alter project_id from VARCHAR to UUID to match projects.id
ALTER TABLE migration_entries
    ALTER COLUMN project_id TYPE UUID USING project_id::uuid;

ALTER TABLE migration_entries
    ADD CONSTRAINT fk_migration_entries_project
    FOREIGN KEY (project_id) REFERENCES projects(id);
