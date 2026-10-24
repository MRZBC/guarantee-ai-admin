-- Apply once to existing databases; new databases use schema.sql.
ALTER TABLE sys_user ADD COLUMN mutation_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_role ADD COLUMN mutation_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_org ADD COLUMN mutation_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_department ADD COLUMN mutation_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE insurance_type ADD COLUMN mutation_version BIGINT NOT NULL DEFAULT 0;
