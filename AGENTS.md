# AI Agent Instructions for Project "Scout Material"

This document provides the core context for the Scout Material Management application. Refer to this document to ensure all generated code aligns with the project's architecture, technology stack, and data model.

## 1. Project Overview

The project is a web application to manage the equipment ("material") for a local scout group.

**Core Features:**
-   **Inventory Management:** Track all equipment, distinguishing between bulk items (like tent pegs) and unique, individual items (like specific tent panels).
-   **Damage Reporting:** Log and track damage for individual items.
-   **Packing Lists ("Kits"):** Create predefined lists of materials needed for events (e.g., everything needed to build a "Jurte").
-   **Location Tracking:** Optionally track where equipment is stored.

## 2. Technology Stack

-   **Backend:** Kotlin with the Ktor framework.
-   **Database:** PostgreSQL. All database interaction should be done through an ORM, preferably JetBrains Exposed (using the DSL).
-   **Frontend:** Svelte with TypeScript.
-   **Deployment:** The application will be containerized using Docker and run behind an Nginx reverse proxy.

## 3. Core Architectural Principles

-   **Backend is a RESTful API:** The backend should expose a clean, stateless RESTful API that serves JSON data. Use standard HTTP verbs (`GET`, `POST`, `PUT`, `DELETE`).
-   **Clear Separation of Concerns:** Use a multi-layer architecture in the backend (e.g., Routes -> Services -> Repositories/DAOs).
-   **Database is the Single Source of Truth:** All application state is persisted in the PostgreSQL database. The schema is the definitive source of truth for all data structures.

## 4. Database Schema

This is the most critical context. The application's logic is built entirely around this PostgreSQL schema.

#### ENUM Types

-   `material_tracking_type`: ENUM with values `'BULK'`, `'INDIVIDUAL'`.
-   `item_condition`: ENUM with values `'NEW'`, `GOOD`, `FAIR`, `POOR`, `DECOMMISSIONED`.

#### **Database Schema Overview for Scout Equipment Management (v3)**

This schema uses a "Class Table Inheritance" pattern. The `materials` table acts as an abstract base, defining common properties. The `individual_items` and `bulk_stock` tables provide the specific, concrete details for each tracking type. To get full details, you must join `materials` with one of the concrete tables based on its `tracking_type`.

#### **ENUM Types**

*   `material_tracking_type`: ENUM with values `'INDIVIDUAL'`, `'BULK'`.
*   `item_condition`: ENUM with values `'NEW'`, `'GOOD'`, `'FAIR'`, `'POOR'`, `'DECOMMISSIONED'`.

#### **Tables**

**1. `storage_locations`** (Unchanged)
*   Stores physical locations.
*   **Columns:** `id` (UUID, PK), `name` (VARCHAR, Unique), `description` (TEXT)

**2. `materials` (The Abstract "Base Class")**
*   The master catalog of all equipment *definitions*. It contains only the shared data.
*   **Columns:**
    *   `id`: UUID, Primary Key
    *   `name`: VARCHAR(255), Not Null, Unique
    *   `tracking_type`: `material_tracking_type`, Not Null
    *   `description`: TEXT, Nullable
    *   `category`: VARCHAR(100), Nullable
    *   `created_at`: TIMESTAMPTZ, Not Null
    *   `updated_at`: TIMESTAMPTZ, Not Null

**3. `individual_items` (Concrete Table for `INDIVIDUAL` type)**
*   Represents a single, physical, uniquely tracked item.
*   **Columns:**
    *   `id`: UUID, Primary Key
    *   `material_id`: UUID, Not Null, Foreign Key to `materials(id) (not unique, -> 1-n)
    *   `identifier`: VARCHAR(100), Not Null
    *   `condition`: `item_condition`, Not Null
    *   `storage_location_id`: UUID, Nullable, Foreign Key to `storage_locations(id)`
    *   `purchase_price`: DECIMAL(10, 2), Nullable
    *   `purchase_date`: DATE, Nullable
    *   `created_at`: TIMESTAMPTZ, Not Null
    *   `updated_at`: TIMESTAMPTZ, Not Null
*   **Constraints:** `UNIQUE(material_id, identifier)`

**4. `bulk_stock` (Concrete Table for `BULK` type)**
*   Represents a stock of a fungible, quantity-tracked material.
*   **Columns:**
    *   `id`: UUID, Primary Key
    *   `material_id`: UUID, Not Null, Unique, Foreign Key to `materials(id)` (unique -> 1-1)
    *   `quantity_available`: INTEGER, Not Null, Default 0
    *   `quantity_damaged`: INTEGER, Not Null, Default 0
    *   `storage_location_id`: UUID, Nullable, Foreign Key to `storage_locations(id)`
    *   `purchase_price`: DECIMAL(10, 2), Nullable (Represents price-per-unit for the stock)
    *   `purchase_date`: DATE, Nullable (Represents purchase date for the stock)
    *   `created_at`: TIMESTAMPTZ, Not Null
    *   `updated_at`: TIMESTAMPTZ, Not Null

**5. `damage_reports`** (Unchanged, but `inventory_item_id` is now `individual_item_id`)
*   A log of damages for `INDIVIDUAL` items.
*   **Columns:**
    *   `id`: UUID, Primary Key
    *   `individual_item_id`: UUID, Not Null, Foreign Key to `individual_items(id)`
    *   ... (other columns are the same)

**6. `kits` & `kit_materials`** (Unchanged)
*   These tables correctly reference the abstract `materials` table, which is exactly what we want for defining recipes.

## 5. Coding Conventions

- Use immutable data structures (`val`, `data class`) where possible. 
- Adhere to standard Kotlin style guides. 
- Use the Exposed DSL for database queries.
- Favor elegant, concise code and functional practices.
- Focus on high-quality, maintainable and correct code.
