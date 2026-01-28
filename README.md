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

This project uses an SQL model to handle the flexibility of Scout tents.

### Hybrid Tracking

Materials are separated into "Individual" and "Bulk" materials.
For each individual material there is a list of items of this "kind".

The API combines the separately tracked materials into one response type in some endpoints.

### The "Kit" Architecture

Unlike a simple Bill of Materials, a Kit (e.g., a Jurte) is composed of:
1.  **Fixed Materials:** Things needed for every variation (e.g., center pole cap).
2.  **Substitution Groups:** Logical "slots" that need to be filled.
* *Example:* A "Roof" group requires `6.0` units.
* A `Jurtenhalbdach` is worth `3.0` units.
* A `Kohtenblatt` is worth `1.00` unit.
* A Substitution group can be optional.
* The frontend then calculates the best combination based on inventory.

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