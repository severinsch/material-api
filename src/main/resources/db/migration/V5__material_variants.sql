-- Material variants: what differs between otherwise equal materials, e.g. ropes of 10, 15 and 20 m, or Kohtenplanen
-- with and without Latz. `materials` keeps what all variants share; stock, items, purchases and loans move to the
-- variant. Every existing material gets one unnamed variant, so nothing changes for its users. Kit options keep
-- referring to the material ("any variant") and may name a specific variant instead.

-- ========= VARIANTS =========

-- Every material has at least one variant. A material with a single variant usually leaves it unnamed, and the UI
-- doesn't show variants for it. `tracking_type` repeats the material's, so the composite keys below keep bulk stock on
-- bulk variants and items on individual ones, and all variants of a material share its type.
CREATE TABLE material_variants
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID                   NOT NULL DEFAULT current_tenant_id(),
    material_id   UUID                   NOT NULL,
    tracking_type material_tracking_type NOT NULL,
    name          VARCHAR(100), -- e.g. "15 m"; NULL only for a material's single variant (checked by the API).
    created_at    TIMESTAMPTZ            NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ            NOT NULL DEFAULT now(),
    -- ON UPDATE CASCADE: the type of a material can't change while it has variants with stock or items anyway.
    CONSTRAINT material_variants_material_id_fkey FOREIGN KEY (material_id, tenant_id, tracking_type)
        REFERENCES materials (id, tenant_id, tracking_type) ON UPDATE CASCADE ON DELETE CASCADE,
    -- Also serves lookups by material_id.
    CONSTRAINT material_variants_name_key UNIQUE (material_id, name),
    CONSTRAINT material_variants_id_tenant_id_key UNIQUE (id, tenant_id),
    CONSTRAINT material_variants_id_tenant_id_tracking_type_key UNIQUE (id, tenant_id, tracking_type),
    -- Target of the kit option key, so options follow a variant that is moved to another material (merging).
    CONSTRAINT material_variants_id_material_id_tenant_id_key UNIQUE (id, material_id, tenant_id)
);

ALTER TABLE material_variants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON material_variants
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

CREATE TRIGGER material_variants_updated_at BEFORE UPDATE ON material_variants FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One unnamed variant per material. The id is new on purpose: if it were the material's id, code that mixes up
-- material and variant ids would pass on migrated data. tenant_id is copied, since current_tenant_id() is NULL while
-- migrations run.
INSERT INTO material_variants (id, tenant_id, material_id, tracking_type, name, created_at, updated_at)
SELECT gen_random_uuid(), tenant_id, id, tracking_type, NULL, created_at, updated_at
FROM materials;


-- ========= DATA: MATERIAL -> VARIANT =========

-- Filling the new columns must not touch updated_at. Only these triggers are disabled: DISABLE TRIGGER ALL would also
-- disable the foreign key checks.
ALTER TABLE individual_items DISABLE TRIGGER individual_items_updated_at;
ALTER TABLE bulk_stock DISABLE TRIGGER bulk_stock_updated_at;
ALTER TABLE purchases DISABLE TRIGGER purchases_updated_at;

ALTER TABLE individual_items ADD COLUMN variant_id UUID;
ALTER TABLE bulk_stock ADD COLUMN variant_id UUID;
ALTER TABLE purchases ADD COLUMN variant_id UUID;
ALTER TABLE loan_lines ADD COLUMN variant_id UUID;

-- Each material has exactly one variant at this point.
UPDATE individual_items t SET variant_id = v.id FROM material_variants v WHERE v.material_id = t.material_id;
UPDATE bulk_stock t SET variant_id = v.id FROM material_variants v WHERE v.material_id = t.material_id;
UPDATE purchases t SET variant_id = v.id FROM material_variants v WHERE v.material_id = t.material_id;
UPDATE loan_lines t SET variant_id = v.id FROM material_variants v WHERE v.material_id = t.material_id;

ALTER TABLE individual_items ENABLE TRIGGER individual_items_updated_at;
ALTER TABLE bulk_stock ENABLE TRIGGER bulk_stock_updated_at;
ALTER TABLE purchases ENABLE TRIGGER purchases_updated_at;


-- ========= CONSTRAINTS =========
-- The old material columns go, together with their constraints and indexes. Constraint names are explicit: the API
-- maps some of them to specific error messages (configure/ErrorHandling.kt).

-- Purchases reference bulk_stock's old key, so they go first.
ALTER TABLE purchases
    DROP CONSTRAINT purchases_material_id_fkey,
    DROP COLUMN material_id;

ALTER TABLE individual_items
    DROP CONSTRAINT individual_items_material_id_tracking_type_fkey,
    DROP CONSTRAINT individual_items_material_id_identifier_key,
    DROP COLUMN material_id,
    ALTER COLUMN variant_id SET NOT NULL,
    -- Unique per variant: plain Kohtenplanen K01… and those with Latz K45 01…, the same identifier in two variants is
    -- fine.
    ADD CONSTRAINT individual_items_variant_id_identifier_key UNIQUE (variant_id, identifier),
    ADD CONSTRAINT individual_items_variant_id_fkey FOREIGN KEY (variant_id, tenant_id, tracking_type)
        REFERENCES material_variants (id, tenant_id, tracking_type) ON DELETE RESTRICT;

-- One stock row per bulk variant (created together with the variant). The location key stays as it is.
ALTER TABLE bulk_stock
    DROP CONSTRAINT bulk_stock_material_id_tracking_type_fkey,
    DROP CONSTRAINT bulk_stock_material_id_tenant_id_key,
    DROP CONSTRAINT bulk_stock_pkey,
    DROP COLUMN material_id,
    ADD CONSTRAINT bulk_stock_pkey PRIMARY KEY (variant_id),
    ADD CONSTRAINT bulk_stock_variant_id_tenant_id_key UNIQUE (variant_id, tenant_id),
    -- The stock is part of the variant itself, so it goes away with it.
    ADD CONSTRAINT bulk_stock_variant_id_fkey FOREIGN KEY (variant_id, tenant_id, tracking_type)
        REFERENCES material_variants (id, tenant_id, tracking_type) ON DELETE CASCADE;

ALTER TABLE purchases
    ALTER COLUMN variant_id SET NOT NULL,
    ADD CONSTRAINT purchases_variant_id_fkey FOREIGN KEY (variant_id, tenant_id)
        REFERENCES bulk_stock (variant_id, tenant_id) ON DELETE RESTRICT;

-- Loans lend specific variants: "3 Seile 15 m", not "3 Seile of any length".
ALTER TABLE loan_lines
    DROP CONSTRAINT loan_lines_check,
    DROP CONSTRAINT loan_lines_loan_id_material_id_key,
    DROP CONSTRAINT loan_lines_material_id_fkey,
    DROP COLUMN material_id,
    ADD CONSTRAINT loan_lines_check CHECK (
        (individual_item_id IS NOT NULL AND variant_id IS NULL AND quantity IS NULL) OR
        (individual_item_id IS NULL AND variant_id IS NOT NULL AND quantity IS NOT NULL)
        ),
    ADD CONSTRAINT loan_lines_loan_id_variant_id_key UNIQUE (loan_id, variant_id),
    ADD CONSTRAINT loan_lines_variant_id_fkey FOREIGN KEY (variant_id, tenant_id)
        REFERENCES material_variants (id, tenant_id) ON DELETE RESTRICT;

-- An option refers to a material, meaning any of its variants including ones added later, or to one specific variant
-- of it. Existing options keep "any variant", which is the same as before with a single variant. A material appears
-- in a group either once as "any variant" or with distinct specific variants (checked by the API).
-- ON UPDATE CASCADE: an option for a specific variant follows it when it's moved to another material (merging).
ALTER TABLE kit_group_options
    ADD COLUMN variant_id UUID,
    DROP CONSTRAINT kit_group_options_pkey,
    ADD CONSTRAINT kit_group_options_group_id_material_id_variant_id_key
        UNIQUE NULLS NOT DISTINCT (group_id, material_id, variant_id),
    ADD CONSTRAINT kit_group_options_variant_id_fkey FOREIGN KEY (variant_id, material_id, tenant_id)
        REFERENCES material_variants (id, material_id, tenant_id) ON UPDATE CASCADE ON DELETE RESTRICT;


-- ========= INDEXES =========
-- Only for foreign keys not already covered by a primary key or unique constraint.

CREATE INDEX idx_purchases_variant_id ON purchases (variant_id);
CREATE INDEX idx_loan_lines_variant_id ON loan_lines (variant_id);
CREATE INDEX idx_kit_group_options_variant_id ON kit_group_options (variant_id, material_id);
