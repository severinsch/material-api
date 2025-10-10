-- ========= CLEANUP: Drop existing objects if they exist =========
-- This makes the script re-runnable.
DROP TABLE IF EXISTS kit_materials CASCADE;
DROP TABLE IF EXISTS kits CASCADE;
DROP TABLE IF EXISTS damage_reports CASCADE;
DROP TABLE IF EXISTS inventory_items CASCADE;
DROP TABLE IF EXISTS materials CASCADE;
DROP TABLE IF EXISTS storage_laocations CASCADE;
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
                                   description TEXT,
                                   created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                   updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The master catalog of all equipment types.
CREATE TABLE materials (
    -- Core Fields
                           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           name VARCHAR(255) NOT NULL UNIQUE,
                           tracking_type material_tracking_type NOT NULL, -- The heart of the logic.

    -- Fields for BULK items
    -- These are only used if tracking_type is 'BULK'.
                           quantity_available INTEGER NOT NULL DEFAULT 0 CHECK (quantity_available >= 0),
                           quantity_damaged INTEGER NOT NULL DEFAULT 0 CHECK (quantity_damaged >= 0),

    -- Common Fields
                           storage_location_id UUID REFERENCES storage_locations(id) ON DELETE SET NULL, -- Optional location for the entire bulk stock.
                           description TEXT,
                           category VARCHAR(100),
                           purchase_price DECIMAL(10, 2),
                           purchase_date DATE,

    -- Timestamps for auditing
                           created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                           updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Represents a single, physical instance of an 'INDIVIDUAL' material.
CREATE TABLE inventory_items (
                                 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 material_id UUID NOT NULL REFERENCES materials(id) ON DELETE RESTRICT, -- Prevent deleting a material if it has items.
                                 storage_location_id UUID REFERENCES storage_locations(id) ON DELETE SET NULL, -- An item's optional, specific location.
                                 identifier VARCHAR(100) NOT NULL, -- The real-world ID, e.g., "K3", "Big-Gas-Cooker".
                                 condition item_condition NOT NULL DEFAULT 'NEW',

    -- Timestamps
                                 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints
                                 UNIQUE(material_id, identifier) -- e.g., You can only have one panel named "K3" for the "Kohten Panel" material.
);

-- A log of specific damages for INDIVIDUAL items.
CREATE TABLE damage_reports (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                inventory_item_id UUID NOT NULL REFERENCES inventory_items(id) ON DELETE CASCADE, -- If an item is deleted, its reports go too.
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
                      description TEXT,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Join table defining the required materials and quantities for a Kit.
CREATE TABLE kit_materials (
                               kit_id UUID NOT NULL REFERENCES kits(id) ON DELETE CASCADE,
                               material_id UUID NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
                               quantity_required INTEGER NOT NULL CHECK (quantity_required > 0),

    -- Constraints
                               PRIMARY KEY (kit_id, material_id) -- A material can only be in a kit once.
);


-- ========= INDEXES: For faster query performance =========

-- Index on material name for quick searching.
CREATE INDEX idx_materials_name ON materials(name);

-- Index on material category for filtering.
CREATE INDEX idx_materials_category ON materials(category);

-- Index on inventory item's material_id for fetching all items of a type.
CREATE INDEX idx_inventory_items_material_id ON inventory_items(material_id);

-- Index on damage report's item_id for fetching a history of damages.
CREATE INDEX idx_damage_reports_inventory_item_id ON damage_reports(inventory_item_id);