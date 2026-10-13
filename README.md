# Scout Material Management - Backend API

A Kotlin-based REST API designed for Scout groups to manage inventory, track material conditions, and calculate packing lists for complex kits (like Jurten and Kohten).

The frontend for this tool can be found [here](https://github.com/severinsch/material-frontend).

## Key Features

* **Hybrid Inventory Tracking:**
* **Advanced Kit Logic:**
* **Damage Reporting:** Workflow for reporting and resolving equipment damages.

## Roadmap

Currently missing but planned are:
* Authentication
* "Borrowing" functionality where people can request to borrow some material for a given time and are automatically reminded to return it.

## Database Model Concepts

The schema is defined in [db/schema.sql](db/schema.sql), which is the single source of truth. The Postgres container
runs it on first start; after changing it, recreate the database with `docker compose down -v && docker compose up -d`.

### Hybrid Tracking

Materials are separated into "Individual" and "Bulk" materials.
For each individual material there is a list of items of this "kind", each with a condition (NEW…POOR) and a
status (ACTIVE, IN_REPAIR, LOST, DECOMMISSIONED). Bulk materials have a single stock row with quantities, and an
optional purchase history.

The database enforces that individual items only belong to individual materials and stock only to bulk materials.
Materials, items and records with history (damage reports, purchases, loans, kit recipes) can't be deleted while
referenced; items are retired via their status instead.

The API combines the separately tracked materials into one response type in some endpoints.

### The "Kit" Architecture

Unlike a simple Bill of Materials, a Kit (e.g., a Jurte) is composed of **groups**: logical "slots" that need a
number of units, filled by one or more interchangeable materials.
* *Example:* A "Roof" group requires `6.0` units.
* A `Jurtenhalbdach` is worth `3.0` units.
* A `Kohtenblatt` is worth `1.00` unit.
* Options with a lower priority value are used first.
* A fixed material ("8x Fenster") is simply a group with a single option worth 1 unit.
* A group can be optional, which is all-or-nothing (e.g. a Jurte with or without windows).

## API Documentation

This API exposes an OpenAPI (Swagger) specification. Once running, access the docs at: http://0.0.0.0:8080/swagger

Note that the API documentation is auto-generated using the built-in Ktor feature.
It is then modified by a script [scripts/FixOpenApiSpec.main.kts] to compensate for missing functionality of the (still experimental) Ktor generation.

## Deployment

### Docker Compose
Use the provided compose file to start both the database and the application.
```bash
docker-compose up -d
```