# Architecture Review: material-api + material-frontend

Review date: 2026-10-13. Covers this repo and `~/projects/material-frontend`.

Items have IDs so they can be referenced and checked off while we work through them.
Status legend: `[ ]` open, `[x]` done, `[-]` decided against / won't do.

## Overall verdict

For a hobby-sized app the foundations are sound. The stack choice is reasonable, the backend is cleanly layered, and the
kit model with substitution groups is the most interesting and valuable part of the design. The weak points are mostly
about **sources of truth and contracts**, not code quality:

- The schema is defined in several places that disagree.
- The API contract is produced by an experimental generator plus regex patching, and is copied between repos by hand.
- The key domain logic (kit allocation) lives in a Svelte page component.
- The data model can't yet support the features on the roadmap (borrowing, multiple locations).

---

## Open domain questions

Answered 2026-10-13.

- [x] **Q1** Is bulk stock of one material split across several locations or purchase batches? (→ DB-3)
  - *Answer:* Usually not split across locations; several purchase batches are conceivable.
- [x] **Q2** Are nested locations needed? (→ DB-7d)
  - *Answer:* No hierarchy. A free-text label (e.g. "shelf compartment X") would be useful.
- [x] **Q3** Should an open damage report make an item unavailable? (→ DB-4)
  - *Answer:* No, damage reports are just informational.
- [x] **Q4** Can kits contain other kits? (→ DB-5)
  - *Answer:* No. Keep it simple and easy to use.
- [x] **Q5** Should item identifiers be globally unique?
  - *Answer:* No, per material is fine (current `UNIQUE(material_id, identifier)` stays).
- [x] **Q6** How complex is borrowing? (→ DB-6)
  - *Answer:* Simple. Basically a book of what was lent and when; no fancy features.
- [x] **Q7** Should currently loaned-out (not yet returned) items count as unavailable? (→ DB-6)
  - *Answer:* Yes if cheap, ideally toggleable. → API returns a separate `loanedCount`; the frontend toggles whether
    to subtract it.

---

## 1. Database schema

### DB-1 One source of truth for the schema — [x]

The schema is defined three times, and the definitions differ:

| | `data/create_db.sql` | Exposed `Models.kt` + `SchemaUtils.create` | `AGENTS.md` |
|---|---|---|---|
| `bulk_stock` | ✔ | **missing from `SchemaUtils.create`** | ✔ |
| ON DELETE CASCADE / SET NULL | ✔ | ✗ (plain FKs) | – |
| CHECK `>= 0` on quantities | ✔ | ✗ | – |
| PG enum types | ✔ | ✗ (`customEnumeration` assumes they exist) | ✔ |
| `created_at` / `updated_at` | ✗ | ✗ | ✔ |
| kit tables | new (fixed + substitution) | new | old `kit_materials` |

On a fresh database the app only works if someone ran `create_db.sql` by hand first, and docker-compose doesn't do that.
If someone instead lets Exposed create the tables, they get a database with different delete behaviour and no
constraints. The SQL script also drops `kit_materials` but not the new kit tables, so it can't be re-run.

There is no production data yet, so the schema can be changed freely for now. Options:

1. A single `schema.sql`, mounted into Postgres' `docker-entrypoint-initdb.d`, `SchemaUtils.create` removed. No new
   dependency, but every change after real data exists needs a manual `ALTER`.
2. Flyway with a single `V1__init.sql` (the same file), run at startup. Keep editing V1 until first deploy; add
   V2, V3, … only once real data exists.

Leaning towards 2, since the roadmap (purchases, loans, auth) guarantees schema changes after data exists, but 1 now
and Flyway at first deploy is also fine.

**Decision (2026-10-13):** option 1, a single `schema.sql` for now. Revisit Flyway once real data exists.

**Original recommendation:** add Flyway (or Liquibase) migrations as the only way to define the schema. Treat Exposed `Table`
objects as mappings of that schema, not its definition, and use `SchemaUtils` only in tests, if at all. Update
`AGENTS.md` to point at the migrations instead of restating the schema.

### DB-2 Enforce the inheritance pattern — [x]

The `materials` / `individual_items` / `bulk_stock` split fits the domain, but the database doesn't enforce the invariants:

- A BULK material can have `individual_items` rows, and an INDIVIDUAL material can have a `bulk_stock` row.
- A BULK material can exist with no `bulk_stock` row (the API then returns nulls or crashes on the cast).

**Fix:** a composite foreign key. Add `UNIQUE (id, tracking_type)` on `materials`, give each child table a
`tracking_type` column fixed by a CHECK (`= 'BULK'` or `= 'INDIVIDUAL'`), and use
`FOREIGN KEY (material_id, tracking_type) REFERENCES materials(id, tracking_type)`. That makes wrong combinations
impossible at the database level.

### DB-3 Bulk stock is 1:1, which limits flexibility — [x] (schema; no API for purchases yet)

`bulk_stock.material_id UNIQUE` means each bulk material has **one location, one purchase price and one purchase date**.
In practice, tent pegs are often split across the trailer and the cellar, and bought in several batches. Two options:

- Make `bulk_stock` 1:n, keyed by `(material_id, storage_location_id)`, effectively stock "lots".
- Go further and keep a **stock movement ledger** (`+20 bought`, `-3 damaged`, `-10 loaned to X`), deriving current
  quantities from it.

The ledger also lays the groundwork for borrowing (DB-6). Right now quantities are overwritten with no history.

**Decision (after Q1):** no lots, no ledger. Lots wouldn't erase the bulk/individual distinction (that's about identity
vs. fungibility), but they cost aggregation in every query plus merge/split UI for a rarely used case. Instead:

- Keep `bulk_stock` 1:1.
- Move purchase info into a separate `purchases` table (material, quantity, unit price, date, note). It's a record of
  what was bought and doesn't drive the stock count, so stock, UI and API stay unchanged. It can replace the purchase
  columns on individual items too, unless per-item purchase data is wanted.
- The rare split-location case is covered by the free-text location label (DB-7d).

Revisit lots only if split locations turn out to be common.

### DB-4 Separate condition, status and damage — [x]

- `item_condition` combines physical condition (NEW…POOR) with lifecycle status (DECOMMISSIONED). As a result,
  decommissioned items appear in "damaged" counts forever. Split into `condition` and `status`
  (e.g. ACTIVE / IN_REPAIR / LOST / DECOMMISSIONED).
- "Damaged" is defined implicitly in `MaterialService` as `POOR or DECOMMISSIONED`, which makes FAIR count as available.
  That's a business rule hidden in a query.
- `damage_reports` aren't connected to anything. An open report doesn't affect availability or condition, and
  `resolved_at`, `resolution_notes` and `reported_by` exist but are never written or exposed.
- Bulk damage is just a number, with no record of what or why. Damage reporting only exists for individual items.

**Decision (after Q3):** damage reports are purely informational and don't affect availability. Remaining work:

- Split DECOMMISSIONED (and possibly LOST) out of `condition` into a `status`, so retired items stop counting as damaged.
- Make the "damaged" rule explicit (which conditions count) instead of hiding it in a query.
- Either wire up `resolved_at` / `resolution_notes` / `reported_by` in the API, or drop them.

### DB-5 Kit model refinements — [x]

No nested kits (Q4).

The substitution-group idea (roof = 6 units, Jurtenhalbdach = 3 units, Kohtenblatt = 1 unit) is good. Refinements:

- [x] **DB-5a** *(decided 2026-10-13)* Fixed materials are just a group with one
  option and `unit_value = 1`. Merging the two concepts removes
  a second code path in every layer (storage, API, allocation). The UI can still show them differently.
- [x] **DB-5b** Clarify group semantics. Previously `min_required_units` defaulted to 0, so groups never triggered the
  shortage warning, and `is_optional` was stored but ignored by the calculator.
  **Decision (2026-10-13):**
  - Drop `min_required_units`. The intended use (e.g. "full set" tent pegs vs. usually used amount) is better handled
    by entering the usually used amount as `required_units`. A group is either complete or short; short always
    triggers the warning.
  - `is_optional` = all-or-nothing toggle per kit in the packing list (e.g. Jurte with or without windows: either one
    per side panel or none). If off, nothing is allocated for it.
  - Fixed materials need to be optional too (windows are a fixed quantity), which is another argument for DB-5a.
- [x] **DB-5c** *(decided 2026-10-13)* `is_preferred` as a boolean can't express "A, then B, then C". Use an integer `priority`.
- [x] **DB-5d** Some booleans (`is_optional`, `is_preferred`) are nullable in the SQL (DEFAULT but no NOT NULL).

### DB-6 Borrowing as a simple loan book — [x] (schema only; no API, no `loanedCount` yet)

**Decision (after Q6):** no reservations and no date-range availability, just a record of what was lent and when.

- `loans`: borrower (free text), lent_at, due_at (nullable), returned_at (nullable), note.
- `loan_lines`: either `individual_item_id`, or `material_id` + `quantity` for bulk (CHECK that exactly one form is used).
- The kit calculation can stay in the frontend (FE-2b dropped).
- Q7: materials get a `loanedCount` (sum over loan lines with `returned_at IS NULL`) next to `availableCount` /
  `damagedCount`. The frontend has a toggle for whether to subtract it (material list + kit calculator).
- Authentication would later turn `reported_by` / borrower into user FKs.

### DB-7 Smaller schema points — [ ]

- [x] **DB-7a** `ON DELETE CASCADE` from `materials` to the kit tables is dangerous: deleting a material silently
  removes it from every kit recipe. Same for `individual_items` → `damage_reports`, which loses damage history. Use
  `RESTRICT` plus soft-delete or archiving for materials and items.
- [x] **DB-7b** `created_at` / `updated_at` are missing everywhere (despite `AGENTS.md`).
- [ ] **DB-7c** `category` is free text, so typos split categories. A lookup table or at least a constraint would help.
- [x] **DB-7d** Locations stay flat (Q2). Add a nullable free-text `location_detail` (e.g. "Regal 3, Fach 2") on
  `individual_items` and `bulk_stock`; it belongs to the stored thing, not the location.
- [x] **DB-7e** Several indexes in the SQL duplicate the ones the UNIQUE constraints already create.

---

## 2. Backend

**Good:** small and readable, Routes → Services split, sealed `Material` type, transactions around multi-table writes,
Exposed DSL used consistently.

### BE-1 Error-handling strategy — [x]

There's no `StatusPages`, so invalid UUIDs in bodies (`parseUUID`), unique-constraint and FK violations,
`LocalDate.parse` failures and `as BulkMaterial` cast failures (e.g. `PUT /materials/bulk/{id}` on an individual
material) all become 500s. Introduce a domain error type mapped to proper 400/404/409 responses in one place.

### BE-2 Input validation — [x]

Negative quantities, `min > required`, empty names are all accepted.

### BE-3 Strongly typed DTOs — [x]

IDs and dates are `String`, money is `Double`. Use UUID/LocalDate serializers and BigDecimal-as-string. This also
removes most of what the OpenAPI fix-up script is patching.

### BE-4 Consistent API shape — [x]

- Creating and updating materials is split by type (`/materials/bulk`, `/materials/individual`), but reading and deleting
  aren't.
- `NewIndividualItem` takes a whole `StorageLocation` object, while `NewBulkMaterial` takes `storageLocationId`.
- Kits have no update or delete endpoint, and locations have no update.
- `POST /kits` returns 200 instead of 201.
- `Material.location` is always null because `StorageLocations` is never joined in `MaterialService`, and it's
  meaningless for individual materials anyway.

### BE-5 OpenAPI pipeline — [x]

The experimental Ktor generator is post-processed by regexes in `scripts/FixOpenApiSpec.main.kts`, and the Docker build
downloads `kotlinc` just to run it. Either use a code-first library that understands sealed types, or write the spec by
hand and make it the contract. Ties into FE-4.

### BE-6 Swagger path breaks in Docker — [x]

The (currently uncommitted) `HTTP.kt` change to `src/main/resources/openapi/open-api.json` doesn't work in the container,
whose working directory is `/app`. Ktor's swagger plugin should find the file on the classpath if given
`"openapi/open-api.json"`, which works both locally and in Docker. Then the Dockerfile no longer needs to copy resources
separately.

### BE-7 Operations — [x]

- [x] Blocking JDBC calls run on the request coroutines; use Exposed's `suspendTransaction` or `Dispatchers.IO`.
- [x] No connection pool (HikariCP).
- [x] Config comes from `System.getenv` with hardcoded default credentials; move into `application.yaml` with env overrides.
- [x] The app starts without a database and only fails on the first request. It should refuse to start instead.
- [x] Empty `Sessions` / `authentication` plugins are installed; CORS allows any host.

### BE-8 Tests — [x]

The only test calls `GET /`, which doesn't exist, so it fails. Integration tests with Testcontainers Postgres around the
services would add the most value, especially for the inheritance rules (DB-2) and kit logic.

---

## 3. Frontend

**Good:** modern stack (Svelte 5 runes, SvelteKit, shadcn-svelte, Tailwind 4), a generated typed client, a usable mobile
view, and solid UX basics (toasts, loading states, confirmation dialogs).

### FE-1 Decide the rendering / data-flow model — [ ]

Loads run server-side (`+page.server.ts`, `API_URL`), but every mutation goes from the browser straight to the API
(`PUBLIC_API_URL`). That's why two URLs, public API exposure and `CORS anyHost` are needed. Pick one:

- **SvelteKit as backend-for-frontend:** form actions or `+server` proxies, API stays internal, natural place for
  session-cookie auth later. *Recommended, since auth is on the roadmap.*
- **Pure SPA:** `adapter-static`, nginx serves the app and `/api` from the same origin, so no CORS. Simpler to run, but
  auth then lives entirely in the API.

### FE-2 Move kit allocation out of the page component — [ ]

The core domain logic lives in `src/routes/kits/+page.svelte`. It's greedy and runs kit by kit (the first kit grabs the
preferred materials), so it's not the "best combination" the README promises. It ignores `is_optional` and isn't tested.

- [ ] **FE-2a** Short term: move into a pure `lib/kits/allocate.ts` with unit tests.
- [-] **FE-2b** ~~Long term (with borrowing): move into the backend.~~ Not needed, since borrowing stays a simple
  loan book (DB-6).

### FE-3 Component structure — [ ]

`src/routes/+page.svelte` is ~1,100 lines: four dialogs, desktop and mobile markup that are near-duplicates, and
duplicated add/edit forms. Extract `MaterialRow`/`MaterialCard`, a shared material form, `ItemForm`, `ConditionSelect`
and `LocationSelect`.

### FE-4 API contract sync — [ ]

The two `open-api.json` copies have already drifted (the frontend has a `StorageLocation` schema the backend's doesn't).
Names like `KitSubstitutionGroupsInner` show the spec lacks named schemas.

- Generate the client from the backend's `/openapi` endpoint or a shared artifact, in a script or CI step.
- Consider `openapi-typescript` + `openapi-fetch` (much lighter, no Java toolchain).
- Don't commit the generated `docs/*.md`.

### FE-5 Missing features vs. backend and README — [ ]

No UI for damage reports, locations, kit creation, bulk location or purchase data. The README advertises
check-in/check-out, which doesn't exist.

### FE-6 Smaller items — [ ]

- [ ] Both `lucide-svelte` and `@lucide/svelte` are installed.
- [ ] `$app/stores` is deprecated in favour of `$app/state`.
- [ ] `$derived(() => …)` is called as a function many times per render; use `$derived.by`.
- [ ] The Dockerfile runner stage runs `bun install --frozen-lockfile` without copying the lockfile.
- [ ] No tests, no CI.

---

## 4. Dependency updates

- [x] **DEP-1 Backend:** Exposed `1.0.0-rc-1` → stable 1.x; Postgres driver pinned inline at `42.7.3` (move to
  `gradle.properties`); Gradle wrapper 8.14; runtime image `temurin:24` is not LTS → 25 LTS; remove the obsolete
  `version:` key from docker-compose.
- [ ] **DEP-2 Frontend:** `bun update` plus shadcn-svelte component refresh. Do FE-4 first so the API client doesn't
  need updating twice.

---

## 5. Suggested order of work

1. ~~Answer the domain questions (Q1–Q7).~~ Done.
2. ~~Single `schema.sql` as source of truth (DB-1), including DB-2, DB-4, DB-7a, DB-7b, DB-7d and the `purchases`
   table (DB-3).~~ Done.
3. ~~Decide kit group semantics (DB-5b).~~ Done, including DB-5a and DB-5c.
4. ~~Backend: BE-1 … BE-8, DEP-1, missing endpoints.~~ Done. Next: FE-4 (client generation).
5. Frontend: FE-1, FE-2a, FE-3.
6. Then auth, then the loan book (DB-6).

---

## Progress log

### 2026-10-13: Schema rework (DB-1 … DB-7)

- `data/create_db.sql` → `db/schema.sql`, mounted into the Postgres container (`docker-entrypoint-initdb.d`).
  `SchemaUtils.create` removed; `Models.kt` only maps the schema.
- New: composite FKs for the inheritance rules, `item_status` enum, `location_detail`, `purchases`, `loans` /
  `loan_lines`, merged kit groups with `priority`, timestamps with an `updated_at` trigger, RESTRICT on history and
  kit references. Verified against a throwaway Postgres 18 (all invalid inserts/deletes rejected).
- Backend adapted and tested end to end (`gradle run` against the new schema):
  - Individual counts: available = ACTIVE and not POOR; damaged = ACTIVE and POOR, or IN_REPAIR; LOST /
    DECOMMISSIONED count as neither.
  - Bulk materials now actually return their location (join was missing).
  - Damage reports: `isResolved` derived from `resolved_at`; `reportedBy`, `resolvedAt`, `resolutionNotes` exposed.
  - Kits API: `fixedMaterials` + `substitutionGroups` replaced by `groups` (**breaking**).
  - Bulk `purchaseDate` / `purchasePrice` removed from the material API (now in `purchases`, no endpoint yet).
  - `encodeDefaults = true`, so fields equal to their default (e.g. `unitValue: 1`) are no longer omitted.

**Follow-ups caused by this:**

- [x] Recreate the local dev database: `docker compose down -v && docker compose up -d` (the existing volume has
  the old schema and the init script only runs on an empty volume).
- [x] Regenerate `open-api.json` (needs `kotlinc` for the fix-up script, not installed locally) and the frontend
  client (FE-4).
- [ ] Frontend kits page: adapt to `groups`, implement the optional toggle, use `priority` (natural moment for FE-2a).
- [ ] Frontend materials page: remove the DECOMMISSIONED condition option, add status and location detail.
- [x] DB constraint violations (e.g. deleting a material used in a kit) currently surface as 500s → BE-1.
- [x] `gradle/wrapper/gradle-wrapper.jar` is missing from the repo, so `./gradlew` doesn't work.
- [x] The untracked `docs/` folder is written by the swagger plugin at runtime; add it to `.gitignore`.
- [ ] `AGENTS.md` (not in the repo) still describes the old schema; point it at `db/schema.sql` instead.

### 2026-10-13: Backend rework (BE-1 … BE-8, DEP-1, new endpoints)

- **Dependencies:** Kotlin 2.4.21, Ktor 3.6.0, Exposed 1.5.1 (+ kotlin-datetime module), Postgres driver 42.7.14,
  HikariCP 7.1, Gradle 9.8.1 (wrapper jar committed), JVM toolchain 21, runtime image Temurin 25 JRE.
- **OpenAPI (BE-5):** Since Ktor 3.4 the document is assembled at runtime from compiler-plugin metadata, with schemas
  from the kotlinx-serialization descriptors. It now produces everything the old regex script patched in (sealed
  `Material` → `oneOf` + `trackingType` discriminator, enums, `format: uuid` / `date`, nullability) and emits OpenAPI
  3.1.1. Static spec, fix-up script and the `kotlinc` download in Docker are gone. Served at `/swagger` and
  `/swagger/documentation.yaml` (JSON despite the name). Quirks found:
  - Types in doc comments must be fully qualified (`[kotlin.uuid.Uuid]`); `[kotlin.Boolean]` drops the parameter,
    `[Boolean]` works (typed as string).
  - Documenting a success response in a comment replaces the inferred schema → only error responses are documented.
  - The nullable `StorageLocation` property type becomes a second schema `StorageLocation2`; location routes are
    registered first so the plain name is the non-null one.
  - Does the improved spec help the frontend? Yes, mainly through the discriminated `Material` union, the enums and
    `ErrorResponse`; `format: uuid`/`date` only matter for documentation (they stay strings in TS). Best consumed by
    a 3.1-capable generator such as `openapi-typescript` (FE-4).
- **API (BE-3/BE-4):** typed ids (`kotlin.uuid.Uuid`, UUIDv7 generated by the app, so ordering by id = insertion
  order), `LocalDate` / `Instant`, validation in model `init` blocks, `IndividualMaterialSummary` → `IndividualMaterial`
  (no location; only bulk materials have one), items take `storageLocationId`, damage reports use `itemId` and
  `resolvedAt` instead of `isResolved`, deletes return 204, creates 201, services throw `NotFoundException` instead of
  returning null. Kept type-specific write endpoints (`/materials/bulk`, `/materials/individual`) since they give
  simple, non-polymorphic request bodies; updating through the wrong type's endpoint is a 404.
- **New endpoints:** `/purchases`, `/loans` (incl. `?open=`), kit `PUT`/`DELETE`, location `PUT`, `GET /{id}` for
  damages, purchases, locations. Materials have `loanedCount` (Q7), items `onLoan`. Loans reject lending an item that
  is on another open loan and bulk quantities beyond the stock (409).
- **Errors (BE-1):** `StatusPages` maps bad input to 400, `NotFoundException` to 404, `ConflictException` and
  restrict / foreign key / unique violations to 409, check violations to 400, all with `{"message": …}`.
- **Operations (BE-6/BE-7):** HikariCP, JDBC on `Dispatchers.IO`, config in `application.yaml` with env overrides,
  fails at startup without database, CORS restricted to `CORS_ALLOWED_HOSTS`, empty auth/session plugins removed,
  `SQL_LOG_LEVEL=DEBUG` for SQL logging. Verified with `docker compose up --build` on a fresh volume.
- **Tests (BE-8):** 14 integration tests against Postgres 18 via Testcontainers (schema constraints, counts, kits,
  loans, damage reports, validation, error mapping).

**Breaking for the frontend** (to do with FE-4):
- [ ] Regenerate the client from `/swagger/documentation.yaml`.
- [ ] Kits: `groups` instead of `fixedMaterials` / `substitutionGroups` (FE-2a).
- [ ] Materials: discriminated union, `IndividualMaterial`, no `location` on individual materials, `loanedCount`.
- [ ] Items: `status`, `storageLocationId` instead of `storageLocation` in requests, no DECOMMISSIONED condition.
- [ ] Deletes return 204; errors have an `ErrorResponse` body.
- [ ] CORS: the frontend's origin must be listed in `CORS_ALLOWED_HOSTS` (FE-1 may make this unnecessary).
