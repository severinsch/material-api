# Scout Material Management - Backend API

A Kotlin-based REST API designed for Scout groups to manage inventory, track material conditions, and calculate packing lists for complex kits (like Jurten and Kohten).

The frontend for this tool can be found [here](https://github.com/severinsch/material-frontend).

## Key Features

* **Hybrid Inventory Tracking:** Bulk materials tracked by quantity, individual items with condition and status.
* **Kits:** Packing lists made of groups of interchangeable materials.
* **Damage Reporting:** Log and resolve damages of individual items.
* **Loan Book:** Record what was lent to whom and when it came back.
* **Purchase History:** Record purchase batches of bulk materials.

## Roadmap

Currently missing but planned is authentication.

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

The OpenAPI document is generated at runtime from the routes (Ktor's OpenAPI support with code inference) and the
kotlinx-serialization models in `src/main/kotlin/api/Models.kt`:

* Swagger UI: http://localhost:8080/swagger
* OpenAPI document (JSON): http://localhost:8080/swagger/documentation.yaml

Routes are documented with KDoc comments. Request and response bodies are inferred from `call.receive`/`call.respond`;
the comments add descriptions, typed parameters and error responses:

```kotlin
/**
 * Get a material.
 *
 * Path: id [kotlin.uuid.Uuid] The material id.
 * Response: 404 application/json [api.ErrorResponse] No material with this id.
 */
```

Quirks of the generator:
* Types in comments must be fully qualified, and descriptions must not contain a colon (the line is dropped).
* Documenting a success response in a comment replaces the inferred schema, so only error responses are documented.
* Query parameters are read through the helpers in `routes/Parameters.kt`, which avoid the pattern the inference
  recognizes; otherwise every documented query parameter would appear a second time without type.
* The metadata is generated at compile time per route file, so after changing `Parameters.kt` run a clean build.

The generated document is committed as [openapi.json](openapi.json) and checked by `OpenApiTest`, so contract
changes are visible in reviews and the frontend can generate its client from it. After an intended API change:

```bash
UPDATE_OPENAPI=1 ./gradlew clean test
```

Errors are returned as `{"message": "..."}` with status 400 (invalid input), 404 (not found) or 409 (conflicts, e.g.
deleting something that is still referenced or lending an item twice).

## Development

```bash
docker compose up -d postgres   # database with db/schema.sql
./gradlew run                   # API on http://localhost:8080
./gradlew test                  # integration tests, need Docker (Testcontainers)
```

### Configuration

| Environment variable | Default | |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/scout_material` | |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `scout` / `scoutpass` | Matches docker-compose.yml |
| `PORT` | `8080` | |
| `SQL_LOG_LEVEL` | `INFO` | `DEBUG` logs all SQL statements |

## Deployment

### Docker Compose
Use the provided compose file to start both the database and the application.
```bash
docker compose up -d --build
```
