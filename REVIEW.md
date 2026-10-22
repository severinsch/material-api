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

Raised 2026-10-19 while entering demo data; not addressed yet.

- [x] **Q8** Groups with a variable amount. A Jurte is built either with an *Einbein* (1 Jurtenstange) or a
  *Dreibein* (3), chosen by what it's used for; nothing else differs. This is a real use for the minimum/maximum
  units dropped in DB-5b. Today the kit has to pick one (the demo Jurte uses 3 and says so in its description), or
  there are two kits.
  - *Answer:* Discrete amounts per group ("Ausführungen"), chosen per line of the packing list; optional groups are
    groups with an amount of 0. See PLAN.md, sections 1 and 2.
- [x] **Q9** Ropes of different lengths. Lengths are stored as separate bulk materials (Seil 10 m, 15 m, 20 m), but
  for most kits any length works, so the kit lists all of them as equal options of one group. Works, but every kit
  repeats the list, and a new length has to be added to every kit. Possibly a material "family" or category that a
  group can refer to.
  - *Answer:* Every material has variants that hold stock, items, purchases and loans; a kit option refers to a
    material (any variant) or to one variant. Existing materials can be merged into variants of one. See PLAN.md,
    sections 1, 3 and 4.

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

**Update (2026-10-15):** switched to option 2 before the first deployment. `db/schema.sql` became
`src/main/resources/db/migration/V1__initial_schema.sql`, applied by Flyway when `Db` is created.

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

### FE-1 Decide the rendering / data-flow model — [x]

Loads run server-side (`+page.server.ts`, `API_URL`), but every mutation goes from the browser straight to the API
(`PUBLIC_API_URL`). That's why two URLs, public API exposure and `CORS anyHost` are needed. Pick one:

- **SvelteKit as backend-for-frontend:** form actions or `+server` proxies, API stays internal, natural place for
  session-cookie auth later. *Recommended, since auth is on the roadmap.*
- **Pure SPA:** `adapter-static`, nginx serves the app and `/api` from the same origin, so no CORS. Simpler to run, but
  auth then lives entirely in the API.

### FE-2 Move kit allocation out of the page component — [x]

The core domain logic lives in `src/routes/kits/+page.svelte`. It's greedy and runs kit by kit (the first kit grabs the
preferred materials), so it's not the "best combination" the README promises. It ignores `is_optional` and isn't tested.

- [x] **FE-2a** Short term: move into a pure `lib/kits/allocate.ts` with unit tests.
- [-] **FE-2b** ~~Long term (with borrowing): move into the backend.~~ Not needed, since borrowing stays a simple
  loan book (DB-6).

### FE-3 Component structure — [x]

`src/routes/+page.svelte` is ~1,100 lines: four dialogs, desktop and mobile markup that are near-duplicates, and
duplicated add/edit forms. Extract `MaterialRow`/`MaterialCard`, a shared material form, `ItemForm`, `ConditionSelect`
and `LocationSelect`.

### FE-4 API contract sync — [x]

The two `open-api.json` copies have already drifted (the frontend has a `StorageLocation` schema the backend's doesn't).
Names like `KitSubstitutionGroupsInner` show the spec lacks named schemas.

- Generate the client from the backend's `/openapi` endpoint or a shared artifact, in a script or CI step.
- Consider `openapi-typescript` + `openapi-fetch` (much lighter, no Java toolchain).
- Don't commit the generated `docs/*.md`.

### FE-5 Missing features vs. backend and README — [x]

No UI for damage reports, locations, kit creation, bulk location or purchase data. The README advertises
check-in/check-out, which doesn't exist.

### FE-6 Smaller items — [x]

- [x] Both `lucide-svelte` and `@lucide/svelte` are installed.
- [x] `$app/stores` is deprecated in favour of `$app/state`.
- [x] `$derived(() => …)` is called as a function many times per render; use `$derived.by`.
- [x] The Dockerfile runner stage runs `bun install --frozen-lockfile` without copying the lockfile.
- [x] No tests, no CI. (Unit tests for the kit allocation; CI is still missing in both repos.)

### FE-7 Kit allocation reports shortages that a different split would avoid — [ ]

Found 2026-10-19 with the demo data. `allocate.ts` fills each group greedily in priority order, so a group can use up
a material that another group needs, even when the first group has alternatives left. Example, with the Jurte roof
preferring Kohtenplanen over Jurtenhalbdächer (stock: 8 Kohtenplanen, 4 with 45 cm Latz, 2 Jurtenhalbdächer): 1 Jurte
+ 2 Kohten reports 2 Kohtenplanen missing. The Jurte takes 6 Kohtenplanen and leaves the Jurtenhalbdächer unused,
although 2 Jurtenhalbdächer + 8 Kohtenplanen (or + 4 Kohtenplanen + 4 with Latz) cover everything.

The demo kits now prefer the Jurtenhalbdach, which hides this case, but any kit set where groups share materials
with different alternatives can hit it. Fix: find a feasible split first and use priorities only to choose among
feasible ones, e.g. a small exact search over the groups' options (the numbers are small), and report a shortage
only if no split covers everything.

### FE-8 Fix an alternative for one kit in the packing list — [ ]

Raised 2026-10-19. It should be possible to pin the option of a group for one selected kit (e.g. this Jurte gets the
Jurtenhalbdächer, that one Kohtenplanen) and let the allocation fill the rest. Best done together with FE-7.

### FE-9 UI improvements — [ ]

Raised 2026-10-19 after entering the demo data; the exact designs are open.

- [ ] **FE-9a** Packing list: the per-kit breakdown per material ("14× für Jurte, 20× für Kohte",
  `[tenant]/kits/+page.svelte`) clutters the list. Redesign, e.g. show it only on demand.
- [ ] **FE-9b** Kits: the list of alternatives per group gets cluttered with more than 2–3 options. Redesign how
  groups and their options are shown.
- [ ] **FE-9c** Top bar: the Stamm's name looks like a clickable tab like the others. Make it visually distinct.
- [ ] **FE-9d** Material table: hard to scan with many materials. Ideas: filter by category with a dropdown, group
  rows by category.

---

## 4. Dependency updates

- [x] **DEP-1 Backend:** Exposed `1.0.0-rc-1` → stable 1.x; Postgres driver pinned inline at `42.7.3` (move to
  `gradle.properties`); Gradle wrapper 8.14; runtime image `temurin:24` is not LTS → 25 LTS; remove the obsolete
  `version:` key from docker-compose.
- [x] **DEP-2 Frontend:** `bun update` plus shadcn-svelte component refresh. Do FE-4 first so the API client doesn't
  need updating twice.

---

## 5. Suggested order of work

1. ~~Answer the domain questions (Q1–Q7).~~ Done.
2. ~~Single `schema.sql` as source of truth (DB-1), including DB-2, DB-4, DB-7a, DB-7b, DB-7d and the `purchases`
   table (DB-3).~~ Done.
3. ~~Decide kit group semantics (DB-5b).~~ Done, including DB-5a and DB-5c.
4. ~~Backend: BE-1 … BE-8, DEP-1, missing endpoints.~~ Done.
5. ~~Frontend: FE-1 … FE-6, DEP-2.~~ Done.
6. Next: CI for both repositories; accounts and tenants (PLAN.md).

---

## 6. Expected future: login and tenants (nothing built yet)

Status: superseded on 2026-10-18. Accounts, password login and tenants are built now, independent of the identity provider; see PLAN.md. The backend owns sessions instead of validating hitobito tokens per request. The notes below remain the background for the later hitobito login (PLAN.md section 7).

- **Identity provider:** the DPSG's new membership platform, expected to be an adapted
  [hitobito](https://hitobito.com/de/). Upstream hitobito is an OAuth 2.0 / OpenID Connect provider
  (`/.well-known/openid-configuration`, authorization code flow with client secret, refresh tokens). With the
  `with_roles` scope, userinfo / profile contain `roles` with `group_id`, `group_name`, `role_name`, `role_class` and
  permissions such as `layer_and_below_full`. Groups form a tree of typed layers. The concrete group and role types come
  from an organisation-specific plugin, so the DPSG claims are unknown until the platform is ready.
- **Frontend (FE-1 decision):** keep the SvelteKit server as backend-for-frontend. Deployment is Docker behind nginx on
  a VPS either way. Hitobito's documented flow needs a client secret, which a pure browser app can't keep; the
  SvelteKit server can do the code exchange, keep tokens server-side, give the browser an httpOnly session cookie and
  attach the access token in the `/api` proxy. Revisit if the platform offers PKCE for public clients and the
  server turns out to be unnecessary.
- **Backend:** validates the access token itself (it must not trust the proxy blindly) and derives the user's Stamm
  and role from the claims, e.g. Vorstand / Materialwart may edit, members may read.
- **Tenants:** probably one inventory per Stamm, fully separated. That means a tenant column (Stamm / hitobito group
  id) on the root tables (`materials`, `storage_locations`, `kits`, `loans`), tenant-scoped unique constraints (e.g.
  material names per Stamm) and filtering in every service. Child tables inherit the tenant through their parent.
  Keep new code free of assumptions that would block this, but don't add the column before the claims are known.
- **Open questions for when the platform is ready:** OIDC details (PKCE, token lifetimes, claim format), how Stämme
  appear as groups or layers, which roles map to edit rights, whether Bezirk / Diözese levels should see or share
  inventories, and whether `reported_by` / `borrower` become user references (DB-6).

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
- [x] Frontend kits page: adapt to `groups`, implement the optional toggle, use `priority` (natural moment for FE-2a).
- [x] Frontend materials page: remove the DECOMMISSIONED condition option, add status and location detail.
- [x] DB constraint violations (e.g. deleting a material used in a kit) currently surface as 500s → BE-1.
- [x] `gradle/wrapper/gradle-wrapper.jar` is missing from the repo, so `./gradlew` doesn't work.
- [x] The untracked `docs/` folder is written by the swagger plugin at runtime; add it to `.gitignore`.
- [-] `AGENTS.md` (not in the repo) still describes the old schema. Obsolete: the file was removed.

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
- [x] Regenerate the client from `/swagger/documentation.yaml`.
- [x] Kits: `groups` instead of `fixedMaterials` / `substitutionGroups` (FE-2a).
- [x] Materials: discriminated union, `IndividualMaterial`, no `location` on individual materials, `loanedCount`.
- [x] Items: `status`, `storageLocationId` instead of `storageLocation` in requests, no DECOMMISSIONED condition.
- [x] Deletes return 204; errors have an `ErrorResponse` body.
- [x] CORS: not needed anymore, the frontend proxies `/api` (FE-1); CORS was removed from the backend.

### 2026-10-13: Fixes from the independent review

An adversarial review of the backend rework found the following; all fixed and covered by tests (16 in total):

- **Errors:** SQL states are read from the whole exception chain (batch inserts wrap them), all database messages are
  replaced by our own, and a catch-all handler returns a generic 500 (Ktor's own statuses like 415 are kept). Parser
  messages are cut to their first line. Unknown or wrong-typed references in bodies are checked by the services
  (`services/References.kt`) and answered with readable 400s; the constraints remain the safety net.
- **Validation:** lengths match the column sizes, prices must be 0…99,999,999.99 with at most 2 decimals.
- **Loans:** only ACTIVE items can be lent (409), existence is checked before availability (404 before 409), and the
  checked item / stock rows are locked (`FOR UPDATE`) so concurrent loans can't overbook.
- **API shape (decided with the owner):**
  - Materials use one resource: `POST /materials`, `PUT /materials/{id}` with a polymorphic body (`trackingType`
    discriminator, mirrors the response); changing the type is a 409.
  - Items are listed via `GET /items?materialId=`; all query parameters are camelCase.
  - Parents can't be changed on update (`itemId` of damage reports, `materialId` of items and purchases) → 400.
  - Kit units are integers (`required_units`, `unit_value`); groups have an explicit `position` and no id in the API.
  - Separate request/response classes where defaults differ (`NewKitGroupOption` / `KitGroupOption`).
  - Enums moved from `database` to `api`.
- **Ids:** one generator (the app, random UUIDs via Exposed's `UuidTable`); the `uuidv7()` defaults are removed and
  nothing depends on id order anymore.
- **OpenAPI contract:** committed as `openapi.json` and checked by `OpenApiTest`. The duplicated query parameters came
  from inference following the parameter helpers; they now read via `request.queryParameters`.

Corrections to the earlier log entry: `[kotlin.Boolean]` works in comments; the parameter was dropped because its
description contained a colon. Inconsistent spec output between builds was caused by stale compile-time metadata
(incremental compilation), hence "clean build" in the README.

Not changed (debatable, kept): validation in model `init` blocks, prices as `Double` (validated), PUT replacing kit
groups and loan lines wholesale.

### 2026-10-13: Frontend rework (FE-1 … FE-6, DEP-2)

- **Dependencies (DEP-2):** SvelteKit 3.0 (config moved from `svelte.config.js` into the Vite plugin, `#lib` subpath
  imports instead of `$lib`, `$app/state`, `refreshAll`, env vars declared in `src/env.ts`), Svelte 5.57, Vite 8,
  TypeScript 6.0 (Kit 3 doesn't support 7 yet), ESLint 10, Tailwind 4.3, `@lucide/svelte` only, shadcn-svelte components
  re-added from the current registry (their `data-checked:` style variants need `shadcn-svelte/tailwind.css`).
- **Data flow (FE-1):** `src/routes/api/[...path]/+server.ts` forwards `/api/*` to `API_URL`; universal loads and
  browser mutations use the same client against it. One URL, no CORS, API not public, and a single place for auth later.
  The client always sends `Content-Type: application/json`, since SvelteKit treats bodyless DELETEs like form posts
  and rejects them on plain-HTTP deployments (adapter-node assumes `https` by default).
- **API client (FE-4):** `openapi-typescript` + `openapi-fetch` from the committed `openapi.json`
  (`bun run api:generate`); the Java generator, its runtime, docs and the stale spec copy are gone. The discriminated
  `Material` union narrows on `trackingType` in TypeScript as intended.
- **Kits (FE-2a):** `lib/kits/allocate.ts` with 6 unit tests (vitest). Groups without alternatives are served first,
  options in priority order, shortages counted on the preferred option, optional groups toggled per kit. Still greedy,
  documented as such.
- **Structure (FE-3/FE-5):** one dialog component per entity (`show(entity?)` opens it for create or edit) on a shared
  `FormDialog`; pages: materials list, material detail (items + damage reports, or stock + purchases), kits
  (packing list + kit editor), loans (loan book with "returned today"), locations. One responsive table instead of
  separate desktop/mobile markup. The "count lent out pieces as unavailable" toggle (Q7) is shared by the materials list
  and the packing list.
- **Backend follow-ups:** loan conflict messages name the item / material instead of its id; CORS removed.
- Verified: `bun run check`, `lint`, `test`, `build`; clicked through all pages against the compose stack; Docker image
  built and run against the API container (pages, POST, DELETE).

### 2026-10-13: Fixes from the frontend review

An independent review of the frontend rework found no severe problems; fixed:

- Packing list: whole pieces are allocated before rounding up (a large piece no longer causes false shortages, test
  added); no iterator helpers (missing on iOS < 18.4).
- `loanedCount` only counts lent pieces that are part of `availableCount` (e.g. not POOR items), so
  `availableCount - loanedCount` is really what's in storage (backend test added).
- Loan dialog: the loan's own items stay listed when unchecked or no longer active, so they can be removed.
- Copying the packing list reports an error without HTTPS instead of failing silently.
- Proxy: forwards the still-encoded path and rejects targets outside `API_URL`'s base path.
- Dates use `de-DE` / `Europe/Berlin` on server and browser alike.

Not done (deliberately): binding the compose ports to localhost and a frontend service in compose (the VPS setup in
`vps-deployments` will cover deployment later), configuring the origin explicitly (adapter-node's `https` default fits
nginx with TLS), proxy timeouts, a `materialId` filter for damage reports, CI.

### 2026-10-13: Restored functionality

Compared against the pre-rework frontend: the inline item view in the materials table, kit availability per line,
pieces per alternative and the selected-kits list were lost in the rework and are back. New: location column
(searchable) and sortable columns in the materials table.

The next round of work (usability fixes, purchases updating the stock, new features) is planned in PLAN.md.

### 2026-10-18: Accounts and tenants, step 1 (schema, RLS, tenant scoping)

- Migration `V2__accounts_and_tenants.sql`: tenants, users, password logins, memberships, sessions, setup tokens;
  `tenant_id` on all tenant-owned tables with the default `current_tenant_id()` (reads `app.tenant_id`), composite
  foreign keys including `tenant_id` (names kept, so the specific error messages still apply), names unique per
  tenant, row-level security with one `tenant_isolation` policy per table, and the role `material_app`.
- Existing data goes to a tenant "Standard". Instead of a nullable column, an `UPDATE` and disabled triggers (as the
  plan suggested), the column is added with the tenant's id as constant default and the default is then replaced:
  existing rows get the id without being updated, so `updated_at` stays without touching the triggers.
- `Db.query` / `Db.tenantQuery`: every transaction runs `SET LOCAL ROLE material_app`, tenant transactions also
  `set_config('app.tenant_id', …, true)`. Startup fails if the role is superuser or `BYPASSRLS`.
- All domain routes below `/tenants/{tenantId}`; services take a `TenantId`, queries are unchanged. A temporary
  route plugin only checks that the tenant exists (replaced by memberships in step 2). The `tenantId` parameter and
  its 404 are documented once by a KDoc on the parent route, which the OpenAPI inference merges into every operation.
- Spikes: `SET LOCAL ROLE` and `set_config` through Exposed/HikariCP, RLS with composite foreign keys on Postgres 18,
  and the OpenAPI inference below a parent route all work as planned.
- Tests: tenant isolation (lists, by-id GET/PUT/DELETE, references in bodies), composite keys rejecting
  cross-tenant references in plain SQL, no rows without tenant, role attributes, the convention check for all tables
  with `tenant_id`, privileges, migration of existing data (tenant assignment, unchanged `updated_at`) and that all
  constraints with specific messages exist.

### 2026-10-18: Accounts and tenants, step 2 (authentication, permissions, bootstrap)

- `ktor-server-auth` with a bearer provider `session` resolving opaque session tokens (SHA-256 stored); sessions end
  after 30 days idle or 180 days, `last_used_at` is written at most hourly. Requests without a valid session get 401
  with a JSON message, and 401 means nothing else (wrong current password when changing it is 400).
- `auth/Passwords.kt`: Argon2id via Bouncy Castle 1.86, PHC strings, OWASP parameters (configurable, cheap in tests),
  at most 2 hashes at a time (503 otherwise). Unknown, pending and disabled users are verified against a dummy hash.
- `auth/LoginThrottle.kt`: per username and client IP, doubling delays after 5 failures (1 s … 15 min, forgotten
  after a day); per IP at most 50 failures per hour. 429 with `Retry-After`. The client IP is the first
  `X-Forwarded-For` entry (the frontend sets it), which the OpenAPI document now lists as optional header. Also
  applied to the current password when changing it.
- Endpoints `/auth/login`, `/auth/logout`, `/auth/me` (GET/PUT), `/auth/me/password`, `/auth/setup/inspect`,
  `/auth/setup` as in the plan. A password change also revokes a pending setup link.
- Permissions: `requires(Permission.X) { … }` creates a transparent child route with a route-scoped plugin and
  documents the 403 in OpenAPI. `tenantRoutes { … }` resolves the membership (platform admins: ADMIN) and refuses to
  start if a route inside has no declaration. Viewer damage reports are restricted in `DamageService.create`.
- Bootstrap (`auth.bootstrap`, off in tests): creates `admin` if there is no platform admin, logs a setup link
  (`FRONTEND_URL/setup#token`) while its password isn't set; `ADMIN_SETUP_LINK_FOR` logs a reset link.
- Spike results: the OpenAPI inference adds the bearer security requirement for routes in `authenticate` and keeps
  working inside custom route functions; `RoutingNode.getAllRoutes()` and `Route.path()` are public, so the startup
  check and the permission matrix test walk the same tree.
- Tests: auth flows (login, identical failures, idle/expired/disabled sessions, logout, profile, password change,
  setup links incl. single use, expiry, disabled users, throttling over HTTP), unit tests for hashing, the semaphore
  and the throttle, bootstrap, the generated permission matrix (every tenant route × viewer/editor/admin/platform
  admin/non-member, and deleted tenants) and viewer damage reports.

### 2026-10-18: Accounts and tenants, step 3 (tenants, users, members)

- Platform admins (`platformAdminRoutes`, outside the tenant routes): `/tenants` (list incl. deleted, create, rename
  and slug, soft delete, restore with 409 on name/slug conflicts) and `/users` (list with memberships, change name,
  username, platform admin flag, disabled; delete; setup link). Disabling ends the user's sessions, so enabling them
  again doesn't revive old ones.
- Tenant members (`VIEW_MEMBERS` / `MANAGE_MEMBERS`): list, create account with setup link, change role and
  optionally name/username, remove (the account stays), new setup link. Platform admins add users of other tenants
  via `PUT /tenants/{t}/members/{userId}`.
- Rules (`services/Accounts.kt`, `MemberService`): tenant admins can change roles of any member, but name, username
  and setup links only of accounts that belong to their tenant alone, and nothing of platform admins (403). Unchanged
  values in an update don't count as change, so a form can send all fields. The last enabled admin of a tenant and the
  last enabled platform admin can't be demoted, removed, disabled or deleted (409); the tenant row (resp. the platform
  admin rows) is locked first, a test lets two admins demote each other concurrently. Setup links are revoked on
  every change of memberships (incl. role), platform admin flag or disabled state.
- Slugs: lowercase with single hyphens, at most 50 characters, not one of the reserved top-level frontend paths
  (`NewTenant.RESERVED_SLUGS`). "Stamm" is the German word for tenant in all messages (decided with the owner).
- `Member` and `User` have `loginMethod` (always `PASSWORD` until hitobito) and `isDisabled`.
- README: accounts, roles, bootstrap, recovery, configuration, error statuses.
- Tests: member management (setup flow, usernames, escalation rules, last admin incl. concurrency, link revocation),
  platform administration (tenant lifecycle incl. restore conflicts, user changes, last admins), platform routes
  generated from the routing tree (tenant admins 403, platform admins pass). 64 tests in total.

### 2026-10-18: Fixes from the independent review of steps 1–3

An adversarial review of the backend found one major and several minor problems; all fixed:

- **Major:** `POST /auth/setup` hashed the password before checking the link, so unauthenticated requests with junk
  tokens could keep both hashing slots busy and every login at 503. The link is now checked first.
- Account changes (member update/remove/setup link, user update/delete/setup link) lock the user's row first, so the
  "belongs to this tenant only" check can't race with a platform admin adding a membership.
- Soft-deleted tenants no longer block disabling or deleting their only admin (they can do without admins).
- The hourly cleanup survives failures (it stopped for good after one exception).
- The permission declaration check runs over the whole routing tree after `routing {}`: a route with the same path
  defined elsewhere ends up below the tenant route without declaration (Ktor merges equal path selectors).
- The convention test also requires an allowlist for tables *without* `tenant_id` and checks the policy expression.
- Credentials are capped (username 100, passwords 200 characters) before hashing.
- `authenticated { }` documents 401 for all routes that need a session.
- `ADMIN_SETUP_LINK_FOR` works independently of `AUTH_BOOTSTRAP`, and is trimmed and lowercased.
- The dummy hash is computed at startup, the client IP is the last `X-Forwarded-For` entry, the first throttling
  delay is 5 s (was 1 s, which also made the HTTP throttling test timing-sensitive), the revocation test checks the
  database instead of the setup page (which hides disabled users anyway), README fixes (role created by V2, debug SQL
  logging shows hashes, the API port must not be published).
- Not changed: binding the compose port to localhost (decided against earlier; production uses vps-deployments).
  Indexes on `tenant_id`: the plan asked for one per root table, but the unique constraints on `(tenant_id, name)`
  already cover `storage_locations`, `materials` and `kits`, so only `loans` got its own.

### 2026-10-18: Accounts and tenants, step 4 (frontend)

material-frontend, branch `accounts`:

- **Sessions (4.1):** `hooks.server.ts` loads `/auth/me` for page requests (not for `/api/*`, where the API checks
  the token itself) and redirects to `/login?redirectTo=…`; `safeRedirectTarget` only accepts paths on the site (unit
  tests incl. `//host`, `/\host`, tab/newline tricks). Login, logout (POST) and setup are form actions; the session
  token only lives in an httpOnly `SameSite=Lax` cookie. The setup page reads the token from the URL fragment and
  sends it to a form action. The proxy has an allowlist (`tenants/**`, `users/**`, `auth/me`, `auth/me/password`),
  adds the bearer token and the client IP, rejects unsafe methods from other origins and clears the cookie on 401.
  Loads redirect to the login on 401 (openapi-fetch middleware); `mutate` keeps the page and offers to log in in a
  new tab. CSP `frame-ancestors 'none'` and `Referrer-Policy: same-origin` on all responses.
- **Tenant routes (4.2):** all pages below `src/routes/[tenant]/`. The tenant layout resolves the slug from the
  user's tenants on every navigation, so membership changes apply without reload; someone who lost access to the
  tenant they were using lands on `/` with a notice (an alert there instead of the toast the plan suggested, since
  `/` usually redirects on). Components get a tenant-bound client from context, typed by stripping
  `/tenants/{tenantId}` from the generated paths, so call sites stay `api.GET('/materials')`. `/` goes to the only or
  last used tenant (cookie) or shows a picker / "no access yet"; old top-level paths redirect via a param matcher.
  Header with tenant switcher and user menu (account, members, administration, logout). Viewers don't see edit
  actions; the damage dialog prefills "Gemeldet von". A test compares the reserved slugs with the route directories.
- **Administration (4.3):** `/[tenant]/members` (editors read, admins create accounts with setup link and copy
  button, change role/name/username, new setup link, remove) and `/admin` for platform admins (tenants: create with
  slug suggestion, rename, delete, restore; accounts: edit incl. platform admin and disabled flags, add to a tenant,
  setup link, delete).
- Verified: `bun run check`, `eslint`, `prettier`, 29 unit tests, `bun run build`; the flows over HTTP against a
  local stack on an empty database (bootstrap link → setup → tenant → member → viewer UI, login/logout, wrong
  password, foreign origin, unknown API paths, reserved slugs, legacy redirects, removed member). Not clicked through
  in a browser (extension unavailable): dialogs, the tenant switcher and the session-expired toast are untested.
- Step 5 (deployment) is open and only happens together with the owner.

### 2026-10-18: Fixes from the independent review of step 4

The review found nothing that lets a user act in another tenant or reach the session token; fixed:

- `safeRedirectTarget` checked for `//` before resolving dot segments, so `/.//evil.example` became `//evil.example`
  (SvelteKit's `redirect()` refused it, giving a 500). Now the normalized result is checked; tests added.
- The root layout's user was only loaded once, so `/` and `/account` showed stale tenants after losing access (the
  picker kept listing the lost tenant). The root layout now reruns on every navigation and the tenant layout reads
  the user from it. Every unknown slug of a logged-in user now leads to `/` with the notice (instead of a 404 unless
  it was the last used tenant), which also covers several open tabs.
- Logout clears the cookie even if the API is down; the setup page can't hang on broken links or non-JSON answers;
  "Undo" toasts stay bound to the tenant of the change; old links keep encoded path segments; no reset link for
  disabled members; small accessibility and wording fixes, comments corrected.
- Not changed: the members list doesn't know which accounts are platform admins or members elsewhere, so tenant
  admins can still open actions the API refuses with an explanatory 403 (would need an extra field in `Member`).
  The reserved slugs stay a copy in the frontend test, so its tests don't depend on the API repository.

### 2026-10-18: Own loans for viewers, password manager fixes

From the owner's first test round:

- **Loans:** viewers no longer see all loans or the overview. New permission `BORROW` (viewers and up): lend to
  oneself, see one's own loans, and change them until they are returned; other loans don't exist for them (404).
  `EDIT` still manages all loans, and deleting needs it. Migration V3 adds `loans.borrower_id` (a user, `ON DELETE
  SET NULL`): editors choose a member as borrower or enter a name for someone without an account; the API stores the
  member's name as a fallback for deleted accounts and otherwise shows their current display name. A borrowing member
  must belong to the tenant, unless they already are the loan's borrower (so old loans stay editable after someone
  leaves). The overview (all open loans, damaged items) is for editors; the nav hides it for viewers.
- **Bitwarden** neither filled in the login nor offered to save it. The login form is now a plain form submission
  (no `use:enhance`), which password managers recognize best, with an explicit `type="text"` username. Setup and
  password change showed the username only in `type="hidden"` fields, which password managers ignore; they now show
  it in a read-only field. To be confirmed by the owner in the browser.
- Verified: backend tests (new: viewer loan rules, member borrowers incl. renamed, removed and deleted accounts),
  frontend check/lint/tests/build, and over HTTP against a throwaway stack (viewer and editor pages, the plain login
  form with right and wrong password). The borrower picker in the loan dialog is untested in a browser.
- Short independent review of these changes: no authorization holes found (other loans, borrower changes,
  reopening, non-members and members of other tenants all refused). Fixed: viewers were offered "Undo" after
  returning a loan, which they can't reopen (403); now only editors get it. Accepted: after an account is deleted,
  its loans show the name as of the last change to the loan, which can predate a later rename (documented on
  `Loans.borrower`).

### 2026-10-22: Kit group amounts (Q8, PLAN.md step 1)

Both repositories, branch `kit-amounts`:

- **Schema (2.1):** V4 adds `kit_group_amounts` (tenant default, composite key to `kit_groups`, RLS policy) and
  drops `kit_groups.required_units`/`is_optional`. A group keeps its amount (label NULL); an optional group becomes
  `ohne` = 0 (position 0, so it stays excluded by default) and `mit` = its units. The INSERT copies `tenant_id` from
  the group. `MigrationTest`: a V3 database with two tenants and optional and non-optional groups; the V1 → latest
  test covers the new table too. `TenantIsolationTest` counts 12 tenant tables and checks the new composite key.
- **API (2.2):** `KitGroup.amounts: List<KitGroupAmount>` (label, requiredUnits) in order, replacing
  `requiredUnits`/`isOptional`; `NewKitGroupAmount` validated in init blocks as planned. A single amount may still
  carry a label (the API keeps it; the frontend sends none). README "Kit" section updated, `openapi.json`
  regenerated.
- **Allocation (2.3):** `allocate` takes lines (`kit`, `quantity`, `choices` per group index) and returns
  `assignments` (`line`, `groupIndex`, `materialId`, `pieces`, `missing`) plus `shortages` per line and group;
  `needsOf` derives the needs with a breakdown per line. Missing pieces are assignments on the preferred option
  marked `missing`, so the needs are a pure sum of the assignments (FE-7/FE-8 can replace the strategy and keep the
  output). Groups with 0 units are skipped.
- **Packing list (2.3):** state is a list of lines; URL `kit=<id>*<qty>[*<group>-<amount>.…]`, one parameter per
  line, only non-default choices. Kit cards list amounts ("Mittelstange: Einbein 1× (Standard) · Dreibein 3×
  Jurtenstange"), keep today's look with the badge "optional" for 0/n groups, and have quantity buttons only for
  kits without choices; others get "Zur Packliste" (adds to the default line) and "n auf der Packliste ·
  Ausführung in der Packliste wählen". "Ausgewählte Sets" shows per line the quantity, remove, one select per group
  with several amounts, and "Weitere mit anderer Ausführung". Line labels like `Jurte (Mittelstange: Einbein,
  Fenster: mit)` in the summary, copied text, print, shortages and the "für …" breakdown.
- **Kit editor (2.4):** one amount looks as before; "Ausführung hinzufügen" switches to rows of label + units (move
  up, remove) with the note that the first is preselected; the "Optional" checkbox inserts `ohne` = 0 first (naming
  a single unnamed amount `mit`) and removes the 0 amounts when unchecked. Client validation mirrors the API.
- **Decisions beyond the plan:**
  - Identical lines are combined *before* allocating, not only for the summary, so shortages and the per-line
    breakdown never list the same label twice. The edit list itself keeps them separate.
  - The URL parser drops invalid choices one by one (as the old parser did with group indices) and keeps the line
    with the default for that group. An old-format value like `jurte*2*2` therefore becomes 2 Jurten with default
    amounts instead of being dropped entirely; harmless since no links were shared.
  - Groups without a name are labelled by their preferred material in line labels and selects ("Fensterplane: mit").
  - Cards of groups with several options *and* several amounts list the options with their unit value instead of a
    piece count, since the pieces depend on the chosen amount.
  - Unchecking "Optional" removes every amount of 0; if nothing remains, one empty amount of 1 is left.
- **Dry run on the synced dump** (`synced-backup/scout_material-2026-10-22.dump`, at V3): restored into a throwaway
  `postgres:18-alpine` container (role `material_app` created first, so the ACLs restore), V4 applied by starting
  the API against it. 26 groups before and after; 29 amounts: the 23 non-optional groups got one row (label NULL,
  same units, 182 in total), the 3 optional ones `ohne`/0 at position 0 and `mit`/their units at position 1 (42 in
  total), every row with its group's tenant. All other tables kept their row counts; `material_app` can access the
  new table and sees the rows with the tenant set (0 without). Container removed afterwards.
- Verified: `UPDATE_OPENAPI=1 ./gradlew clean test` (69 tests); frontend `bun run check`, `eslint .`, `prettier`
  on all tracked files, `bun run test` (34 tests), `bun run build`. Over HTTP against a throwaway stack (API +
  `bun run dev`): created and updated a kit with Einbein/Dreibein, an optional window group and a plain group
  through the frontend proxy, read it back, got the German 400 for duplicate labels; the kits page rendered with
  two lines of the same kit (`kit=<id>*2&kit=<id>*1*0-1.1-1`): card text, selects, labels in summary and print, the
  breakdown "60× für Jurte (…Einbein…), 30× für Jurte (…Dreibein…)", the shortage on the Dreibein line; identical
  lines combined to "3× Jurte (…)"; the kit editor rendered the amount rows and the Optional state.
  `bun run lint` as a whole fails only on the local, git-ignored `.claude/settings.local.json` (not in
  `.prettierignore`).
- Not verified: nothing was clicked in a browser (extension not connected): changing a select, "Weitere mit anderer
  Ausführung", the card buttons, the URL update while editing, and the editor's Optional shortcut and amount rows
  are untested interactively.

### 2026-10-22: Material variants, schema and backend (Q9, PLAN.md step 2)

Backend only, branch `variants`; the frontend doesn't compile against the new API until step 3. The merge endpoint
(4.4) is step 4.

- **Schema (3):** V5 adds `material_variants` (tenant default, RLS policy, `updated_at` trigger, composite key to
  `materials` incl. `tracking_type`, the planned unique keys) and moves `bulk_stock` (new PK `variant_id`, location key
  kept with `ON DELETE SET NULL (storage_location_id)`), `individual_items` (identifiers unique per variant),
  `purchases` (→ `bulk_stock (variant_id, tenant_id)`) and `loan_lines` (CHECK recreated) from `material_id` to
  `variant_id`. `kit_group_options` keeps `material_id` and gets a nullable `variant_id` with the key
  `(variant_id, material_id, tenant_id)` → variants `ON UPDATE CASCADE ON DELETE RESTRICT` and
  `UNIQUE NULLS NOT DISTINCT (group_id, material_id, variant_id)` instead of the PK. Each material gets one unnamed
  variant with a new id, `tenant_id`, `tracking_type`, `created_at` and `updated_at` copied; the three `updated_at`
  triggers of the updated tables are disabled by name while filling the columns. Indexes for the new foreign key
  columns. `MigrationTest`: a V4 database with two tenants and data in every touched table → variants, every row's
  material (through its variant), tenant and data, `updated_at`, old columns gone, location deletion; the V1 → latest
  test covers `material_variants` too. `TenantIsolationTest` counts 13 tables, checks cross-tenant variants in the
  composite keys (variants, items, quantity loan lines, kit options) and in request bodies (items incl. moving one,
  purchases, loans, kit options, a foreign variant listed in a material update).
- **API (4.1–4.3):** as planned: `BulkMaterial`/`IndividualMaterial` with `variants` (`BulkVariant` with stock and
  location, `IndividualVariant`), counts summed; `NewBulkMaterial`/`NewIndividualMaterial` with the full variant list
  and `removedVariantIds`; `variantId` on items (plus read-only `materialId`), purchases (plus `materialId`), loan
  quantities and kit options (nullable = any variant). `GET /items` and `GET /purchases` keep the `materialId` filter
  (all variants). Availability, locks (`lockVariants`) and stock checks work per variant; messages use one helper
  (`services/Names.kt`). README "Hybrid Tracking" and "Kit" sections updated, `openapi.json` regenerated.
- **Decisions beyond the plan:**
  - Display names include the variant name only if the material has several variants, also for a single *named*
    variant (the plan's item rule applied to variants as well), since clients don't show variants then.
  - Variants are sorted in Kotlin (`variantNameOrder`: numbers by value, text case-insensitively, the unnamed one
    first), not with an ICU collation in the schema. Items are sorted by material, variant (same order), identifier.
  - Removing a referenced variant is checked by `MaterialService` with messages naming the variant ("Die Variante
    15 m hat Einkäufe…", "…wird in den Sets Jurte, Kohte verwendet…"). The constraint messages keep the material
    wording, since deleting a material (which cascades to its variants) is the path that reaches them.
  - Ids in `removedVariantIds` that aren't the material's own variants are rejected (400) like listed ones, not
    ignored. On create, ids and `removedVariantIds` are rejected. Listing a variant and removing it is a 400.
  - When several variants are renamed, their names are cleared first, so swapping names works despite the unique key.
  - A purchase can't move to another variant (400), like it couldn't move to another material.
  - Kit options: one overlap rule replaces "Ein Material kann in einem Teil nur einmal vorkommen": per material
    either a single "any variant" option or distinct variants ("Die Materialien eines Teils überschneiden sich…").
    `NewKitGroupOption` takes `variantId` as second parameter, as in the plan's `KitGroupOption`.
  - `material_variants_material_id_fkey` has `ON UPDATE CASCADE` as planned, although only the kit option key's
    cascade matters for the merge (it moves variants by updating `material_variants.material_id`).
  - The 409 description of `PUT /materials/{id}` is on one line: the generator keeps only the first line (the old
    text was cut off in `openapi.json`).
- **Dry run on the synced dump** (`synced-backup/scout_material-2026-10-22.dump`, at V3): restored into a throwaway
  `postgres:18-alpine` container (role `material_app` created first), V4 and V5 applied by starting the API against
  it (Flyway, 42 ms). Row counts before and after are identical: storage_locations 3, materials 28, individual_items
  82, bulk_stock 19, purchases 2, damage_reports 4, kits 4, kit_groups 26, kit_group_options 45, loans 2, loan_lines
  9, tenants 2, users 2, tenant_members 1; new: material_variants 28 (one unnamed per material, tenant and type of the
  material, no id equal to its material's), kit_group_amounts 29, all kit options "any variant", loan lines 5
  quantities and 4 items. Per material (28 rows), computed by SQL before and after: available, damaged, loaned,
  location, place, number of purchases, identifiers and number of kit options identical; `updated_at` of items,
  stock, purchases and materials unchanged. The new API (started against the copy, with a session row inserted for
  the platform admin there) returned the same available/damaged/loaned/location for all 28 materials; items, kits,
  loans and purchases answered 200 for both tenants; adding a variant to "Seil 10 m" through `PUT` worked.
  `material_app` sees the 28 variants with the tenant set and none without; deleting a location (rolled back) nulled
  it in 7 stock rows and kept their tenant. Container removed afterwards.
- Verified: `UPDATE_OPENAPI=1 ./gradlew clean test` (84 tests, 15 new: `VariantApiTest` for variant create, update,
  removal and its conflicts, name and id validation, name swaps, per-variant stock checks, identifiers and moving
  items, purchases and loans per variant; `KitApiTest` for options by material or variant and the overlap rule;
  `MigrationTest` V4 → V5).
- Not verified: nothing against the frontend (step 3); no concurrency tests for the variant locks. The merge (4.4)
  isn't implemented.
