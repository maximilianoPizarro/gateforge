-- V2: Repair schema - recreate tables if they were lost while flyway_schema_history survived
-- Uses IF NOT EXISTS so it's safe to run even if tables already exist

CREATE TABLE IF NOT EXISTS migration_plans (
    id                   VARCHAR(36)  NOT NULL PRIMARY KEY,
    gateway_strategy     VARCHAR(50),
    source_products      TEXT,
    ai_analysis          TEXT,
    created_at           TIMESTAMP WITH TIME ZONE,
    catalog_info_yaml    TEXT,
    status               VARCHAR(50),
    target_cluster_id    VARCHAR(255),
    target_cluster_label VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS generated_resources (
    id        BIGSERIAL    NOT NULL PRIMARY KEY,
    kind      VARCHAR(100),
    name      VARCHAR(255),
    namespace VARCHAR(255),
    yaml      TEXT,
    plan_id   VARCHAR(36)  REFERENCES migration_plans(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS audit_entries (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    timestamp         TIMESTAMP WITH TIME ZONE,
    action            VARCHAR(50),
    resource_kind     VARCHAR(100),
    resource_name     VARCHAR(255),
    namespace         VARCHAR(255),
    yaml_before       TEXT,
    yaml_after        TEXT,
    performed_by      VARCHAR(255),
    target_cluster_id VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_generated_resources_plan_id ON generated_resources(plan_id);
CREATE INDEX IF NOT EXISTS idx_audit_entries_timestamp ON audit_entries(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_entries_action ON audit_entries(action);
CREATE INDEX IF NOT EXISTS idx_audit_entries_cluster ON audit_entries(target_cluster_id);
CREATE INDEX IF NOT EXISTS idx_migration_plans_status ON migration_plans(status);
CREATE INDEX IF NOT EXISTS idx_migration_plans_cluster ON migration_plans(target_cluster_id);
CREATE INDEX IF NOT EXISTS idx_migration_plans_created ON migration_plans(created_at DESC);
