-- Database schema for Scout Material Management.
--
-- This file is the single source of truth for the database structure. The Exposed table objects in
-- src/main/kotlin/database/Models.kt only map it and must be kept in sync by hand.
--
-- It is run automatically by the Postgres container on first start (empty data volume).
-- To recreate the database after changing this file: `docker compose down -v && docker compose up -d`.
--
-- Requires PostgreSQL 18+ (uuidv7()).

-- ========= ENUMS =========

-- Whether a material is managed by quantity (BULK) or as distinct, identifiable items (INDIVIDUAL).
CREATE TYPE material_tracking_type AS ENUM ('BULK', 'INDIVIDUAL');

-- Physical condition of an individual item.
CREATE TYPE item_condition AS ENUM ('NEW', 'GOOD', 'FAIR', 'POOR');

-- Lifecycle status of an individual item. Items with history are retired via status instead of being deleted.
CREATE TYPE item_status AS ENUM ('ACTIVE', 'IN_REPAIR', 'LOST', 'DECOMMISSIONED');


-- ========= HELPERS =========

CREATE FUNCTION set_updated_at() RETURNS trigger AS
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END
$$ LANGUAGE plpgsql;


-- ========= LOCATIONS =========

-- Physical storage locations like "Garage" or "Keller". Finer placement (shelf, compartment) is stored as free text
-- in `location_detail` on the stored thing.
CREATE TABLE storage_locations
(
    id          UUID PRIMARY KEY      DEFAULT uuidv7(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);


-- ========= MATERIALS (class table inheritance) =========

-- The catalog of all equipment definitions, the abstract "base class".
-- `tracking_type` decides which concrete table holds the details. Concrete tables reference (id, tracking_type), so
-- a BULK material can never get individual items and vice versa, and the type of a material can't change while it
-- has concrete rows.
CREATE TABLE materials
(
    id            UUID PRIMARY KEY                DEFAULT uuidv7(),
    name          VARCHAR(255)           NOT NULL UNIQUE,
    tracking_type material_tracking_type NOT NULL,
    description   TEXT,
    category      VARCHAR(100),
    created_at    TIMESTAMPTZ            NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ            NOT NULL DEFAULT now(),
    UNIQUE (id, tracking_type)
);

-- A single, physical, uniquely identifiable item of an INDIVIDUAL material.
CREATE TABLE individual_items
(
    id                  UUID PRIMARY KEY                DEFAULT uuidv7(),
    material_id         UUID                   NOT NULL,
    tracking_type       material_tracking_type NOT NULL DEFAULT 'INDIVIDUAL' CHECK (tracking_type = 'INDIVIDUAL'),
    -- Unique per material only, e.g. "1", "2", "A".
    identifier          VARCHAR(100)           NOT NULL,
    condition           item_condition         NOT NULL DEFAULT 'GOOD',
    status              item_status            NOT NULL DEFAULT 'ACTIVE',
    storage_location_id UUID                   REFERENCES storage_locations (id) ON DELETE SET NULL,
    location_detail     VARCHAR(255),
    purchase_price      DECIMAL(10, 2) CHECK (purchase_price >= 0),
    purchase_date       DATE,
    created_at          TIMESTAMPTZ            NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ            NOT NULL DEFAULT now(),
    UNIQUE (material_id, identifier),
    FOREIGN KEY (material_id, tracking_type) REFERENCES materials (id, tracking_type) ON DELETE RESTRICT
);

-- The stock of a BULK material. Exactly one row per bulk material (created together with the material).
CREATE TABLE bulk_stock
(
    material_id         UUID PRIMARY KEY,
    tracking_type       material_tracking_type NOT NULL DEFAULT 'BULK' CHECK (tracking_type = 'BULK'),
    quantity_available  INTEGER                NOT NULL DEFAULT 0 CHECK (quantity_available >= 0),
    quantity_damaged    INTEGER                NOT NULL DEFAULT 0 CHECK (quantity_damaged >= 0),
    storage_location_id UUID                   REFERENCES storage_locations (id) ON DELETE SET NULL,
    location_detail     VARCHAR(255),
    created_at          TIMESTAMPTZ            NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ            NOT NULL DEFAULT now(),
    -- The stock is part of the material itself, so it goes away with it.
    FOREIGN KEY (material_id, tracking_type) REFERENCES materials (id, tracking_type) ON DELETE CASCADE
);

-- Purchase history of BULK materials (several batches are possible). Purely informational: it does not change the
-- stock quantities. Individual items store their purchase data on the item itself.
CREATE TABLE purchases
(
    id            UUID PRIMARY KEY        DEFAULT uuidv7(),
    -- References bulk_stock, which only exists for BULK materials.
    material_id   UUID           NOT NULL REFERENCES bulk_stock (material_id) ON DELETE RESTRICT,
    quantity      INTEGER        NOT NULL CHECK (quantity > 0),
    unit_price    DECIMAL(10, 2) CHECK (unit_price >= 0),
    purchase_date DATE,
    note          TEXT,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);


-- ========= DAMAGE REPORTS =========

-- An informational log of damages to individual items. Reports don't affect availability; that is expressed via
-- the item's condition and status.
CREATE TABLE damage_reports
(
    id                 UUID PRIMARY KEY     DEFAULT uuidv7(),
    individual_item_id UUID        NOT NULL REFERENCES individual_items (id) ON DELETE RESTRICT,
    description        TEXT        NOT NULL,
    reported_by        VARCHAR(255), -- Will become a user reference once authentication exists.
    reported_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A report is resolved iff resolved_at is set.
    resolved_at        TIMESTAMPTZ,
    resolution_notes   TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- ========= KITS =========

-- A "packing list" / recipe, e.g. everything needed for one Jurte.
CREATE TABLE kits
(
    id          UUID PRIMARY KEY      DEFAULT uuidv7(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A slot in a kit that needs `required_units` units, filled by one or more interchangeable materials.
-- A fixed material ("8x Fenster") is a group with a single option of unit_value 1 and usually no name.
-- Optional groups are all-or-nothing: they are either packed completely or not at all.
CREATE TABLE kit_groups
(
    id             UUID PRIMARY KEY        DEFAULT uuidv7(),
    kit_id         UUID           NOT NULL REFERENCES kits (id) ON DELETE CASCADE,
    name           VARCHAR(255), -- e.g. "Dach"; NULL for single-material groups.
    required_units DECIMAL(10, 2) NOT NULL CHECK (required_units > 0),
    is_optional    BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (kit_id, name)
);

-- How much one piece of a material contributes to a group, and in which order options are used.
CREATE TABLE kit_group_options
(
    group_id    UUID           NOT NULL REFERENCES kit_groups (id) ON DELETE CASCADE,
    -- RESTRICT: deleting a material must not silently change kit recipes.
    material_id UUID           NOT NULL REFERENCES materials (id) ON DELETE RESTRICT,
    unit_value  DECIMAL(10, 2) NOT NULL DEFAULT 1 CHECK (unit_value > 0),
    -- Lower values are used first.
    priority    INTEGER        NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, material_id)
);


-- ========= LOANS =========

-- A simple record of lent material. A loan is open until returned_at is set.
CREATE TABLE loans
(
    id          UUID PRIMARY KEY      DEFAULT uuidv7(),
    borrower    VARCHAR(255) NOT NULL, -- Will become a user reference once authentication exists.
    lent_at     DATE         NOT NULL DEFAULT current_date,
    due_at      DATE CHECK (due_at >= lent_at),
    returned_at DATE CHECK (returned_at >= lent_at),
    note        TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- What was lent: either one individual item, or a quantity of a bulk material.
CREATE TABLE loan_lines
(
    id                 UUID PRIMARY KEY DEFAULT uuidv7(),
    loan_id            UUID NOT NULL REFERENCES loans (id) ON DELETE CASCADE,
    individual_item_id UUID REFERENCES individual_items (id) ON DELETE RESTRICT,
    -- References bulk_stock, which only exists for BULK materials.
    bulk_material_id   UUID REFERENCES bulk_stock (material_id) ON DELETE RESTRICT,
    quantity           INTEGER CHECK (quantity > 0),
    CHECK (
        (individual_item_id IS NOT NULL AND bulk_material_id IS NULL AND quantity IS NULL) OR
        (individual_item_id IS NULL AND bulk_material_id IS NOT NULL AND quantity IS NOT NULL)
        ),
    UNIQUE (loan_id, individual_item_id),
    UNIQUE (loan_id, bulk_material_id)
);


-- ========= INDEXES =========
-- Only for foreign keys not already covered by a primary key or unique constraint.

CREATE INDEX idx_materials_category ON materials (category);
CREATE INDEX idx_damage_reports_individual_item_id ON damage_reports (individual_item_id);
CREATE INDEX idx_purchases_material_id ON purchases (material_id);
CREATE INDEX idx_kit_group_options_material_id ON kit_group_options (material_id);
CREATE INDEX idx_loan_lines_individual_item_id ON loan_lines (individual_item_id);
CREATE INDEX idx_loan_lines_bulk_material_id ON loan_lines (bulk_material_id);


-- ========= TRIGGERS =========

CREATE TRIGGER storage_locations_updated_at BEFORE UPDATE ON storage_locations FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER materials_updated_at BEFORE UPDATE ON materials FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER individual_items_updated_at BEFORE UPDATE ON individual_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER bulk_stock_updated_at BEFORE UPDATE ON bulk_stock FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER purchases_updated_at BEFORE UPDATE ON purchases FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER damage_reports_updated_at BEFORE UPDATE ON damage_reports FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER kits_updated_at BEFORE UPDATE ON kits FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER kit_groups_updated_at BEFORE UPDATE ON kit_groups FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER loans_updated_at BEFORE UPDATE ON loans FOR EACH ROW EXECUTE FUNCTION set_updated_at();
