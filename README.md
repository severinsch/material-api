# Scout Material Management - Backend API

A Kotlin-based REST API designed for Scout groups to manage inventory, track material conditions, and calculate packing lists for complex kits (like Jurten and Kohten).

The frontend for this tool can be found [here](https://github.com/severinsch/material-frontend).

## Key Features

* **Hybrid Inventory Tracking:** Bulk materials tracked by quantity, individual items with condition and status.
* **Kits:** Packing lists made of groups of interchangeable materials.
* **Damage Reporting:** Log and resolve damages of individual items.
* **Loan Book:** Record what was lent to whom and when it came back: quantities ("4 Kohtenblätter, 10 Heringe") or,
  where it matters, specific items.
* **Purchase History:** Record purchase batches of bulk materials; purchased quantities are added to the stock.
* **Accounts and tenants:** Each Stamm has its own, fully separated inventory. Members log in with username and
  password and have a role per Stamm.

## Roadmap

Login via the DPSG's membership platform (hitobito) is planned once it is available; accounts and tenants are
already independent of it (see PLAN.md, section 7).

## Accounts and Permissions

Users log in with username and password (`POST /auth/login`) and get an opaque session token, sent as
`Authorization: Bearer <token>`. Sessions end after 30 days without use and after 180 days at most. Passwords are
hashed with Argon2id; failed logins are throttled per username and client IP.

Each user has a role per tenant:

| Role | May |
|---|---|
| `VIEWER` | read inventory, kits and locations, report damage (without resolving it or retiring items), lend to themselves and see and change their own open loans |
| `EDITOR` | also change inventory, damage reports, kits and locations, manage all loans, and see the members |
| `ADMIN` | also manage the members of the tenant |

Loans to members are linked to their account (`borrowerId`), so they see them as their own; loans to someone
without an account only have a name.

Platform admins have full access to every tenant and manage tenants and all accounts (`/tenants`, `/users`). Every
tenant route declares its permission with `requires(Permission.X) { … }` (`configure/Security.kt`); the application
doesn't start if one is missing, and `PermissionTest` checks all of them for every role.

Accounts are created by admins, who get a single-use setup link to pass on (no email); the user sets their own
password with it. The same kind of link resets a forgotten password. Links are valid 7 days for new accounts and 24
hours for resets, and are revoked when the user's memberships, platform admin flag or disabled state change.

**Bootstrap:** on startup, if there is no platform admin, the API creates the user `admin` and logs a setup link at
WARN level (`docker compose logs api`), renewed on every start until its password is set. **Recovery:** if the only
platform admin forgot their password, start the API once with `ADMIN_SETUP_LINK_FOR=<username>` to log a new link.

## Database Model Concepts

The schema is defined by the [Flyway](https://documentation.red-gate.com/flyway) migrations in
[src/main/resources/db/migration](src/main/resources/db/migration), which are the single source of truth. The API
applies pending migrations when it starts, so a deployment only needs an empty database.

To change the schema, add a migration `V<n>__<description>.sql` with the next number. Migrations that already ran
against real data must not be edited: Flyway refuses to start if an applied migration changed. Locally, a changed
migration is fine after resetting the database with `docker compose down -v && docker compose up -d postgres`.

### Tenants

Each inventory belongs to a tenant (usually a Stamm), and the API is scoped by tenant: `/tenants/{tenantId}/materials`.
Tenants are isolated by Postgres row-level security: the application runs every transaction as the role
`material_app` (created by the migrations, without login), and the policies only show and accept rows of the tenant
set for the transaction (`Db.tenantQuery`). Services don't filter by tenant themselves. Without a tenant
(`Db.query`, used for accounts and platform administration), tenant-owned tables appear empty.

A new tenant-owned table needs, in its migration:

* `tenant_id UUID NOT NULL DEFAULT current_tenant_id()`, so inserts get the current tenant. Exposed doesn't map the
  column.
* Foreign keys to other tenant-owned tables that include `tenant_id`, e.g.
  `FOREIGN KEY (kit_id, tenant_id) REFERENCES kits (id, tenant_id)`. Foreign key checks ignore row-level security, so
  these keys are what prevents references to another tenant's rows. Root tables reference `tenants (id)` instead.
* `ALTER TABLE … ENABLE ROW LEVEL SECURITY` and the policy
  `CREATE POLICY tenant_isolation ON … USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())`.
* Explicit names for constraints that have specific error messages (`constraintMessages` in `ErrorHandling.kt`).

`material_app` gets access to new tables automatically (default privileges). `TenantIsolationTest` checks the
convention for all tables with a `tenant_id` column.

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
* A group can have several amounts ("Ausführungen"), each with a label: a Jurte's "Mittelstange" needs 1
  Jurtenstange as "Einbein" or 3 as "Dreibein". The packing list picks one per kit, the first one by default. All
  amounts share the group's options.
* An optional group is a group with an amount of 0, e.g. "ohne" = 0 and "mit" = 12 Fensterplanen.

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

Errors are returned as `{"message": "..."}` with status 400 (invalid input), 401 (no valid session; nothing else),
403 (missing permission), 404 (not found, also for tenants the user has no access to), 409 (conflicts, e.g. deleting
something that is still referenced or lending an item twice), 429 (too many failed logins, with `Retry-After`) or 503
(too many password hashes at once).

## Development

```bash
docker compose up -d postgres   # empty database, the API creates the schema
./gradlew run                   # API on http://localhost:8080
./gradlew test                  # integration tests, need Docker (Testcontainers)
```

### Configuration

| Environment variable | Default | |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/scout_material` | |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `scout` / `scoutpass` | Matches docker-compose.yml |
| `PORT` | `8080` | |
| `SQL_LOG_LEVEL` | `INFO` | `DEBUG` logs all SQL statements with their values, including password and token hashes: only for debugging |
| `FRONTEND_URL` | `http://localhost:5173` | Base URL of the frontend, for the setup links in the log |
| `AUTH_BOOTSTRAP` | `true` | Create the first platform admin on startup, see above |
| `ADMIN_SETUP_LINK_FOR` | | Log a new setup link for this platform admin on startup (independent of `AUTH_BOOTSTRAP`) |

The database user must be allowed to create roles (`CREATEROLE`, which the superuser of the official Postgres image
has): the migrations create the role `material_app`, which the application uses for all queries.

## Deployment

### Docker Compose
Use the provided compose file to start both the database and the application.
```bash
docker compose up -d --build
```

The API must only be reachable from the frontend (don't publish its port): it trusts the `X-Forwarded-For` header
the frontend sets for the throttling of logins.
