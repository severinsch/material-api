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

#### Tables

**1. `storage_locations`**
-   Stores physical locations where equipment can be stored.
-   `id` (UUID, PK), `name` (VARCHAR, Unique), `description` (TEXT)

**2. `materials`**
-   The master catalog of all equipment *types*.
-   `id` (UUID, PK), `name` (VARCHAR, Unique), `tracking_type` (ENUM), `storage_location_id` (UUID, FK, Nullable)
-   **For `BULK` items only:** `quantity_available` (INT), `quantity_damaged` (INT)
-   **Descriptive fields:** `description` (TEXT), `category` (VARCHAR), `purchase_price` (DECIMAL), `purchase_date` (DATE)

**3. `inventory_items`**
-   A single, physical instance of an `INDIVIDUAL` material.
-   `id` (UUID, PK), `material_id` (UUID, FK), `storage_location_id` (UUID, FK, Nullable), `identifier` (VARCHAR), `condition` (ENUM)
-   **Constraints:** `UNIQUE(material_id, identifier)`

**4. `damage_reports`**
-   A log of damages for `INDIVIDUAL` items.
-   `id` (UUID, PK), `inventory_item_id` (UUID, FK), `description` (TEXT), `is_resolved` (BOOL), `reported_at` (TIMESTAMPTZ), etc.

**5. `kits`**
-   Defines a "packing list" or "recipe".
-   `id` (UUID, PK), `name` (VARCHAR, Unique), `description` (TEXT)

**6. `kit_materials`**
-   Join table for materials required for a `Kit`.
-   `kit_id` (UUID, FK), `material_id` (UUID, FK), `quantity_required` (INT)
-   **Constraints:** `PRIMARY KEY (kit_id, material_id)`

## 5. Coding Conventions

- Use immutable data structures (`val`, `data class`) where possible. 
- Adhere to standard Kotlin style guides. 
- Use the Exposed DSL for database queries.
- Favor elegant, concise code and functional practices.
- Focus on high-quality, maintainable and correct code.
