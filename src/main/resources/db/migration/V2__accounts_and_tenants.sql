-- Accounts, sessions and tenants.
--
-- Every inventory belongs to a tenant (usually a Stamm). Tenant-owned tables have a `tenant_id` that defaults to the
-- tenant of the current transaction, and row-level security only shows and accepts rows of that tenant. The
-- application runs all transactions as the role `material_app`, which is subject to these policies; the user running
-- the migrations owns the tables and is only used by Flyway.
--
-- Convention for later migrations (see README): a new tenant-owned table gets
--   tenant_id UUID NOT NULL DEFAULT current_tenant_id(),
-- composite foreign keys that include tenant_id, and the policy `tenant_isolation`. TenantIsolationTest checks it.


-- ========= TENANTS AND ACCOUNTS =========

CREATE TYPE tenant_role AS ENUM ('VIEWER', 'EDITOR', 'ADMIN');

-- One inventory, usually a Stamm. Soft deleted, so the platform admin can restore it.
CREATE TABLE tenants
(
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    -- Used in frontend URLs.
    slug       VARCHAR(50)  NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Deleted tenants don't block names and slugs; restoring checks for conflicts.
CREATE UNIQUE INDEX tenants_name_key ON tenants (name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX tenants_slug_key ON tenants (slug) WHERE deleted_at IS NULL;

CREATE TABLE users
(
    id                UUID PRIMARY KEY,
    display_name      VARCHAR(255) NOT NULL,
    is_platform_admin BOOLEAN      NOT NULL DEFAULT FALSE,
    disabled_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Login with username and password. Users without a row here log in via an external provider (later).
CREATE TABLE password_logins
(
    user_id       UUID PRIMARY KEY REFERENCES users ON DELETE CASCADE,
    username      VARCHAR(100) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9._-]{3,100}$'),
    -- Argon2id in PHC string format. NULL until the user redeemed their setup link.
    password_hash TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Not tenant-owned in the sense of row-level security: memberships are read before a tenant is chosen.
CREATE TABLE tenant_members
(
    tenant_id  UUID        NOT NULL REFERENCES tenants ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users ON DELETE CASCADE,
    role       tenant_role NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);
CREATE INDEX idx_tenant_members_user_id ON tenant_members (user_id);

-- Tokens are random 256-bit values given to the client once; only their SHA-256 is stored.
CREATE TABLE sessions
(
    id           UUID PRIMARY KEY,
    token_hash   BYTEA       NOT NULL UNIQUE,
    user_id      UUID        NOT NULL REFERENCES users ON DELETE CASCADE,
    -- 'PASSWORD', later 'HITOBITO' (lifetimes may differ).
    login_method VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Absolute limit; the idle limit is checked against last_used_at.
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_sessions_user_id ON sessions (user_id);

-- Single-use links to set a password (new accounts and resets). At most one per user.
CREATE TABLE setup_tokens
(
    user_id    UUID PRIMARY KEY REFERENCES password_logins ON DELETE CASCADE,
    token_hash BYTEA       NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER tenants_updated_at BEFORE UPDATE ON tenants FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER password_logins_updated_at BEFORE UPDATE ON password_logins FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tenant_members_updated_at BEFORE UPDATE ON tenant_members FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ========= TENANT OF THE CURRENT TRANSACTION =========

-- Set by the application with set_config('app.tenant_id', ..., true). NULL if none is set: tenant-owned tables then
-- appear empty and inserts fail.
CREATE FUNCTION current_tenant_id() RETURNS UUID AS
$$
SELECT nullif(current_setting('app.tenant_id', true), '')::uuid
$$ LANGUAGE sql STABLE;


-- ========= TENANT_ID ON TENANT-OWNED TABLES =========

-- Existing data is assigned to a tenant "Standard", which the platform admin renames afterwards. The constant column
-- defaults below fill in its id for existing rows without touching them (so updated_at stays), and are replaced by
-- current_tenant_id() afterwards. On an empty database no tenant is created.
INSERT INTO tenants (id, name, slug)
SELECT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73', 'Standard', 'standard'
WHERE EXISTS (SELECT FROM storage_locations)
   OR EXISTS (SELECT FROM materials)
   OR EXISTS (SELECT FROM kits)
   OR EXISTS (SELECT FROM loans);

-- Foreign keys between tenant-owned tables are recreated below including tenant_id.
ALTER TABLE individual_items
    DROP CONSTRAINT individual_items_material_id_tracking_type_fkey,
    DROP CONSTRAINT individual_items_storage_location_id_fkey;
ALTER TABLE bulk_stock
    DROP CONSTRAINT bulk_stock_material_id_tracking_type_fkey,
    DROP CONSTRAINT bulk_stock_storage_location_id_fkey;
ALTER TABLE purchases DROP CONSTRAINT purchases_material_id_fkey;
ALTER TABLE damage_reports DROP CONSTRAINT damage_reports_individual_item_id_fkey;
ALTER TABLE kit_groups DROP CONSTRAINT kit_groups_kit_id_fkey;
ALTER TABLE kit_group_options
    DROP CONSTRAINT kit_group_options_group_id_fkey,
    DROP CONSTRAINT kit_group_options_material_id_fkey;
ALTER TABLE loan_lines
    DROP CONSTRAINT loan_lines_loan_id_fkey,
    DROP CONSTRAINT loan_lines_individual_item_id_fkey,
    DROP CONSTRAINT loan_lines_material_id_fkey;

ALTER TABLE storage_locations ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE materials ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE individual_items ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE bulk_stock ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE purchases ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE damage_reports ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE kits ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE kit_groups ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE kit_group_options ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE loans ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';
ALTER TABLE loan_lines ADD COLUMN tenant_id UUID NOT NULL DEFAULT '7d4f2c1e-9b3a-4e8f-a6d5-0c2b1e9f8a73';

ALTER TABLE storage_locations ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE materials ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE individual_items ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE bulk_stock ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE purchases ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE damage_reports ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE kits ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE kit_groups ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE kit_group_options ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE loans ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();
ALTER TABLE loan_lines ALTER COLUMN tenant_id SET DEFAULT current_tenant_id();


-- ========= CONSTRAINTS =========
-- Row-level security doesn't apply to foreign key checks, so the composite keys are what keeps rows from referencing
-- another tenant's rows. Constraint names are explicit and partly kept from V1: the API maps some of them to specific
-- error messages (configure/ErrorHandling.kt).

-- Root tables.
ALTER TABLE storage_locations
    ADD CONSTRAINT storage_locations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    DROP CONSTRAINT storage_locations_name_key,
    ADD CONSTRAINT storage_locations_name_key UNIQUE (tenant_id, name),
    ADD CONSTRAINT storage_locations_id_tenant_id_key UNIQUE (id, tenant_id);

ALTER TABLE materials
    ADD CONSTRAINT materials_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    DROP CONSTRAINT materials_name_key,
    ADD CONSTRAINT materials_name_key UNIQUE (tenant_id, name),
    DROP CONSTRAINT materials_id_tracking_type_key,
    ADD CONSTRAINT materials_id_tenant_id_key UNIQUE (id, tenant_id),
    ADD CONSTRAINT materials_id_tenant_id_tracking_type_key UNIQUE (id, tenant_id, tracking_type);

ALTER TABLE kits
    ADD CONSTRAINT kits_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    DROP CONSTRAINT kits_name_key,
    ADD CONSTRAINT kits_name_key UNIQUE (tenant_id, name),
    ADD CONSTRAINT kits_id_tenant_id_key UNIQUE (id, tenant_id);

ALTER TABLE loans
    ADD CONSTRAINT loans_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    ADD CONSTRAINT loans_id_tenant_id_key UNIQUE (id, tenant_id);

-- Child tables. Location references only null the location, not tenant_id.
ALTER TABLE individual_items
    ADD CONSTRAINT individual_items_id_tenant_id_key UNIQUE (id, tenant_id),
    ADD CONSTRAINT individual_items_material_id_tracking_type_fkey FOREIGN KEY (material_id, tenant_id, tracking_type)
        REFERENCES materials (id, tenant_id, tracking_type) ON DELETE RESTRICT,
    ADD CONSTRAINT individual_items_storage_location_id_fkey FOREIGN KEY (storage_location_id, tenant_id)
        REFERENCES storage_locations (id, tenant_id) ON DELETE SET NULL (storage_location_id);

ALTER TABLE bulk_stock
    ADD CONSTRAINT bulk_stock_material_id_tenant_id_key UNIQUE (material_id, tenant_id),
    ADD CONSTRAINT bulk_stock_material_id_tracking_type_fkey FOREIGN KEY (material_id, tenant_id, tracking_type)
        REFERENCES materials (id, tenant_id, tracking_type) ON DELETE CASCADE,
    ADD CONSTRAINT bulk_stock_storage_location_id_fkey FOREIGN KEY (storage_location_id, tenant_id)
        REFERENCES storage_locations (id, tenant_id) ON DELETE SET NULL (storage_location_id);

ALTER TABLE purchases
    ADD CONSTRAINT purchases_material_id_fkey FOREIGN KEY (material_id, tenant_id)
        REFERENCES bulk_stock (material_id, tenant_id) ON DELETE RESTRICT;

ALTER TABLE damage_reports
    ADD CONSTRAINT damage_reports_individual_item_id_fkey FOREIGN KEY (individual_item_id, tenant_id)
        REFERENCES individual_items (id, tenant_id) ON DELETE RESTRICT;

ALTER TABLE kit_groups
    ADD CONSTRAINT kit_groups_id_tenant_id_key UNIQUE (id, tenant_id),
    ADD CONSTRAINT kit_groups_kit_id_fkey FOREIGN KEY (kit_id, tenant_id)
        REFERENCES kits (id, tenant_id) ON DELETE CASCADE;

ALTER TABLE kit_group_options
    ADD CONSTRAINT kit_group_options_group_id_fkey FOREIGN KEY (group_id, tenant_id)
        REFERENCES kit_groups (id, tenant_id) ON DELETE CASCADE,
    ADD CONSTRAINT kit_group_options_material_id_fkey FOREIGN KEY (material_id, tenant_id)
        REFERENCES materials (id, tenant_id) ON DELETE RESTRICT;

-- Nullable references work as before: a foreign key is skipped when one of its columns is NULL (MATCH SIMPLE).
ALTER TABLE loan_lines
    ADD CONSTRAINT loan_lines_loan_id_fkey FOREIGN KEY (loan_id, tenant_id)
        REFERENCES loans (id, tenant_id) ON DELETE CASCADE,
    ADD CONSTRAINT loan_lines_individual_item_id_fkey FOREIGN KEY (individual_item_id, tenant_id)
        REFERENCES individual_items (id, tenant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT loan_lines_material_id_fkey FOREIGN KEY (material_id, tenant_id)
        REFERENCES materials (id, tenant_id) ON DELETE RESTRICT;

-- List queries of the root tables filter by tenant; the unique constraints on (tenant_id, name) cover the others.
CREATE INDEX idx_loans_tenant_id ON loans (tenant_id);


-- ========= ROW-LEVEL SECURITY =========

ALTER TABLE storage_locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE materials ENABLE ROW LEVEL SECURITY;
ALTER TABLE individual_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE bulk_stock ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchases ENABLE ROW LEVEL SECURITY;
ALTER TABLE damage_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE kits ENABLE ROW LEVEL SECURITY;
ALTER TABLE kit_groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE kit_group_options ENABLE ROW LEVEL SECURITY;
ALTER TABLE loans ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_lines ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON storage_locations
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON materials
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON individual_items
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON bulk_stock
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON purchases
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON damage_reports
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON kits
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON kit_groups
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON kit_group_options
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON loans
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON loan_lines
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());


-- ========= APPLICATION ROLE =========

-- Roles exist per cluster, so it may exist already (e.g. another database on the same server).
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'material_app') THEN
            CREATE ROLE material_app NOLOGIN;
        END IF;
    END
$$;
GRANT material_app TO CURRENT_USER;

GRANT USAGE ON SCHEMA public TO material_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO material_app;
REVOKE ALL ON flyway_schema_history FROM material_app;
-- Tables of later migrations (run by the same user).
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO material_app;
