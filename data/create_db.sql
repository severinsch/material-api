-- ========= CLEANUP: Drop existing objects if they exist =========
-- This makes the script re-runnable.
DROP TABLE IF EXISTS kit_materials CASCADE;
DROP TABLE IF EXISTS kits CASCADE;
DROP TABLE IF EXISTS damage_reports CASCADE;
DROP TABLE IF EXISTS bulk_stock CASCADE;
DROP TABLE IF EXISTS individual_items CASCADE;
DROP TABLE IF EXISTS materials CASCADE;
DROP TABLE IF EXISTS storage_locations CASCADE;
DROP TYPE IF EXISTS material_tracking_type;
DROP TYPE IF EXISTS item_condition;


-- ========= ENUMS: Custom data types for data integrity =========

-- Defines if a material is managed by quantity (BULK) or as distinct items (INDIVIDUAL)
CREATE TYPE material_tracking_type AS ENUM ('BULK', 'INDIVIDUAL');

-- Defines the physical condition of a specific, tracked inventory item
CREATE TYPE item_condition AS ENUM ('NEW', 'GOOD', 'FAIR', 'POOR', 'DECOMMISSIONED');


-- ========= TABLES: The core structure of the application =========

-- Table to store physical storage locations like "Garage" or "Keller"
CREATE TABLE storage_locations (
                                   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                   name VARCHAR(100) NOT NULL UNIQUE,
                                   description TEXT
);

-- The master catalog of all equipment DEFINITIONS (The Abstract "Base Class").
-- Contains only shared information.
CREATE TABLE materials (
                           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           name VARCHAR(255) NOT NULL UNIQUE,
                           tracking_type material_tracking_type NOT NULL,
                           description TEXT,
                           category VARCHAR(255)
);

-- Concrete table for 'INDIVIDUAL' tracked items.
CREATE TABLE individual_items (
                                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- A one-to-many relationship with the materials table.
                                  material_id UUID NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
                                  identifier VARCHAR(100) NOT NULL,
                                  condition item_condition NOT NULL DEFAULT 'NEW',
                                  storage_location_id UUID REFERENCES storage_locations(id) ON DELETE SET NULL,
                                  purchase_price DECIMAL(10, 2),
                                  purchase_date DATE,

    -- An identifier must be unique within its material category.
    -- This constraint is better handled at the application level now, but a DB-level
    -- unique constraint on (material_id, identifier) is still good practice.
                                  UNIQUE(material_id, identifier)
);

-- Concrete table for 'BULK' tracked items.
CREATE TABLE bulk_stock (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- A one-to-one relationship with the materials table.
                            material_id UUID NOT NULL UNIQUE REFERENCES materials(id) ON DELETE CASCADE,
                            quantity_available INTEGER NOT NULL DEFAULT 0 CHECK (quantity_available >= 0),
                            quantity_damaged INTEGER NOT NULL DEFAULT 0 CHECK (quantity_damaged >= 0),
                            storage_location_id UUID REFERENCES storage_locations(id) ON DELETE SET NULL,
                            purchase_price DECIMAL(10, 2), -- Price per unit for this stock
                            purchase_date DATE
);

-- A log of specific damages for INDIVIDUAL items.
-- NOTE: Foreign key now correctly points to 'individual_items'.
CREATE TABLE damage_reports (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                individual_item_id UUID NOT NULL REFERENCES individual_items(id) ON DELETE CASCADE,
                                description TEXT NOT NULL,
                                reported_by VARCHAR(255), -- Will become a user_id later.
                                is_resolved BOOLEAN NOT NULL DEFAULT FALSE,
                                resolution_notes TEXT,
    -- Timestamps
                                reported_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                resolved_at TIMESTAMPTZ
);

-- Defines a "packing list" or a "recipe" for an activity.
CREATE TABLE kits (
                      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                      name VARCHAR(255) NOT NULL UNIQUE,
                      description TEXT
);

-- Join table defining the required materials and quantities for a Kit.
CREATE TABLE kit_materials (
                               kit_id UUID NOT NULL REFERENCES kits(id) ON DELETE CASCADE,
                               material_id UUID NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
                               quantity_required INTEGER NOT NULL CHECK (quantity_required > 0),
                               PRIMARY KEY (kit_id, material_id)
);


-- ========= INDEXES: For faster query performance =========
CREATE INDEX idx_materials_name ON materials(name);
CREATE INDEX idx_materials_category ON materials(category);
CREATE INDEX idx_individual_items_material_id ON individual_items(material_id);
CREATE INDEX idx_bulk_stock_material_id ON bulk_stock(material_id);
CREATE INDEX idx_damage_reports_individual_item_id ON damage_reports(individual_item_id);