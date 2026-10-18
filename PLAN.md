# Plan: accounts, login and tenants

The usability round (previous content of this file) is done; see git history and the progress log in REVIEW.md.
This version includes the findings of an independent review of the first draft (2026-10-18).

Covers both repositories; frontend work is marked **(frontend)**, deployment work **(deploy)**.

Status: `[ ]` open, `[x]` done, `[?]` waiting for a decision.

---

## 0. Why now, and what waits for hitobito

The app is live at `material.dpsg-langenbach.de` without any authentication. Local accounts are needed permanently
anyway (platform admin, people without hitobito access), and retrofitting a tenant column gets more expensive with
every feature. What REVIEW.md §6 rightly wanted to avoid was coupling the data to hitobito's still unknown claims. This
plan avoids that: tenants and users are our own entities with our own ids, and hitobito later becomes *one way to log
in* plus a *mapping* from its groups/roles onto our tenants/roles.

Built now: users, password login, sessions, tenants, memberships, roles, tenant isolation, admin UI.
Later (section 7): the hitobito login and claim mapping.

---

## 1. Decisions

Decided with the owner (2026-10-18):

- **Backend owns authentication.** Ktor checks passwords (and later does the OIDC code exchange with hitobito, holding
  the client secret) and issues its own opaque session tokens. SvelteKit only holds the token in an httpOnly cookie and
  forwards it as `Authorization: Bearer`. Every login method ends in the same internal session. Replaces the §6 idea of
  validating hitobito access tokens on every request (they are opaque Doorkeeper tokens, so each request would cost a
  call to hitobito, and local accounts would need a second mechanism).
- **Tenant in the path.** API: `/tenants/{tenantId}/materials…`; frontend: `/{slug}/materials…`. Every page and request
  is self-contained: a tab left open on Stamm A can't write into Stamm B after switching tenants in another tab (which
  happens with a session- or cookie-stored "active tenant", mostly to admins). Links sent to an admin for support open
  the right tenant. Users with a single tenant are redirected there from `/`.
- **Tenant isolation by Postgres row-level security (RLS)**, the database-side equivalent of Hibernate's automatic
  tenant filter: every tenant-owned table has a policy that only shows and accepts rows of the tenant set for the
  current transaction. Services don't filter by tenant themselves, and forgetting a filter is impossible, including
  in hand-written SQL. Without a tenant set, queries return nothing. Details in 2.3.
- **Setup links for local accounts.** An admin creates an account and gets a single-use link to pass on; the user sets
  their own password. The same mechanism is the admin-triggered password reset. No email.
- **Roles per tenant:** `VIEWER` (read everything, report damage), `EDITOR` (change inventory, loans, damage reports,
  kits, locations; see the member list; expected to be Materialwart and Vorstand), `ADMIN` (also manage the tenant's
  members). Plus a global **platform admin** flag with full access to every tenant (no separate "support access").
- **Hitobito accounts and local accounts are separate accounts.** A hitobito account's memberships come only from
  hitobito, a local account's only from admins. No merging, no mixed memberships (section 7).
- **Tenants are soft deleted** (`deleted_at`), restorable by the platform admin. No hard delete for now.
- **Passwords:** at least 12 characters, no further rules and no blocklist (low-value data, volunteers).
- Redirecting old bookmarks isn't needed (no users yet); old top-level paths redirect into the tenant as a small
  convenience (4.2).

Defaults chosen in this plan (cheap to change):

- Usernames are global, lowercase `[a-z0-9._-]`, 3–100 characters (no collation issues, login needs no tenant).
- Requests to a tenant the user isn't a member of, or to a deleted tenant, get 404, not 403.
- Sessions expire after 30 days without use and after 180 days at most.
- Only the platform admin can change a slug; old links then break.
- 401 means exactly "no valid session". Wrong credentials inside a valid session (e.g. the current password when
  changing it) are 400, so the frontend can safely end the session on every 401.
- `reported_by` / `borrower` stay free text; the frontend prefills `reported_by` with the user's name. User references
  (DB-6) and an audit trail are out of scope.

---

## 2. Schema (migration `V2__accounts_and_tenants.sql`)

### 2.1 New tables

```sql
CREATE TYPE tenant_role AS ENUM ('VIEWER', 'EDITOR', 'ADMIN');

-- One inventory, usually a Stamm. Own ids; the hitobito group is mapped later via an extra column.
CREATE TABLE tenants (
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    slug       VARCHAR(50)  NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),  -- used in frontend URLs
    deleted_at TIMESTAMPTZ,
    created_at, updated_at
);
-- Deleted tenants don't block names and slugs; restoring checks for conflicts.
CREATE UNIQUE INDEX tenants_name_key ON tenants (name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX tenants_slug_key ON tenants (slug) WHERE deleted_at IS NULL;

CREATE TABLE users (
    id                UUID PRIMARY KEY,
    display_name      VARCHAR(255) NOT NULL,
    is_platform_admin BOOLEAN      NOT NULL DEFAULT FALSE,
    disabled_at       TIMESTAMPTZ,
    created_at, updated_at
);

-- Login with username and password. Users without a row here log in via an external provider (later).
CREATE TABLE password_logins (
    user_id       UUID PRIMARY KEY REFERENCES users ON DELETE CASCADE,
    username      VARCHAR(100) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9._-]{3,100}$'),
    password_hash TEXT,          -- NULL until the user redeemed their setup link
    created_at, updated_at
);

CREATE TABLE tenant_members (
    tenant_id UUID        NOT NULL REFERENCES tenants ON DELETE CASCADE,
    user_id   UUID        NOT NULL REFERENCES users ON DELETE CASCADE,
    role      tenant_role NOT NULL,
    created_at, updated_at,
    PRIMARY KEY (tenant_id, user_id)
);
CREATE INDEX idx_tenant_members_user_id ON tenant_members (user_id);

-- Tokens are random 256-bit values given to the client once; only their SHA-256 is stored.
CREATE TABLE sessions (
    id           UUID PRIMARY KEY,
    token_hash   BYTEA       NOT NULL UNIQUE,
    user_id      UUID        NOT NULL REFERENCES users ON DELETE CASCADE,
    login_method VARCHAR(20) NOT NULL,     -- 'PASSWORD', later 'HITOBITO' (lifetimes may differ)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL      -- absolute limit; idle limit is checked against last_used_at
);
CREATE INDEX idx_sessions_user_id ON sessions (user_id);

-- Single-use links to set a password (new accounts and resets). At most one per user.
CREATE TABLE setup_tokens (
    user_id    UUID PRIMARY KEY REFERENCES password_logins ON DELETE CASCADE,
    token_hash BYTEA       NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`updated_at` triggers for all new tables with that column.

### 2.2 `tenant_id` on every tenant-owned table

All of `storage_locations`, `materials`, `individual_items`, `bulk_stock`, `purchases`, `damage_reports`, `kits`,
`kit_groups`, `kit_group_options`, `loans`, `loan_lines` get
`tenant_id UUID NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid`, so inserts get the current
tenant without the application setting it.

- Root tables reference `tenants (id) ON DELETE RESTRICT`.
- Every foreign key between tenant-owned tables becomes composite and includes `tenant_id`, like the existing
  `tracking_type` technique. RLS doesn't apply to foreign key checks, so these keys are what prevents referencing
  another tenant's row (a kit option with a foreign material, a loan line with a foreign item, an item in a foreign
  location). Required unique constraints on the referenced side:
  - `materials`: `UNIQUE (id, tenant_id)` (kit options, loan lines) and `UNIQUE (id, tenant_id, tracking_type)`
    (items, stock); the old `UNIQUE (id, tracking_type)` goes away.
  - `bulk_stock`: `UNIQUE (material_id, tenant_id)` (purchases).
  - `storage_locations`, `individual_items`, `kits`, `kit_groups`, `loans`: `UNIQUE (id, tenant_id)`.
- Location references use `ON DELETE SET NULL (storage_location_id)` (Postgres 15+), otherwise `tenant_id` would be
  nulled too. Nullable composite keys in `loan_lines` work as before (MATCH SIMPLE skips them when a column is null).
- **Constraint names:** all constraints are named explicitly, keeping the names used in `constraintMessages`
  (`configure/ErrorHandling.kt`), e.g. `purchases_material_id_fkey`, `materials_name_key`. Otherwise Postgres'
  generated names change and every specific error message silently falls back to the generic one.
- Unique names become per tenant: `UNIQUE (tenant_id, name)` on `materials`, `storage_locations`, `kits`.
- Indexes on `tenant_id` of the root tables for list queries (RLS adds `tenant_id = …` to every query).
- Exposed: `tenant_id` isn't mapped in `Tables.kt`, like `created_at`; the database fills and checks it.

### 2.3 Row-level security

- The migration creates a `NOLOGIN` role `material_app` (if it doesn't exist), grants it the needed privileges on all
  application tables and grants the role to the migrating user.
- `ENABLE ROW LEVEL SECURITY` on every tenant-owned table, with one policy each:
  `USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)` and the same `WITH CHECK`.
- `Db` gets two entry points; every application transaction starts with `SET LOCAL ROLE material_app`, the owner role
  is only used by Flyway:
  - `query(block)`: no tenant set, for auth and platform code. Tenant tables appear empty there (fails closed).
  - `tenantQuery(tenant, block)`: additionally `SELECT set_config('app.tenant_id', ?, true)`. Both settings are
    transaction-local, so pooled connections can't carry a tenant over.
- `ALTER DEFAULT PRIVILEGES` grants `material_app` access to tables created by later migrations. Convention for
  every later migration (documented in the README next to the migration rules): a new tenant-owned table gets
  `tenant_id` with the default, composite foreign keys and the policy. A test enforces it: every table with a
  `tenant_id` column has RLS enabled and a policy, and `material_app` can access every application table.
- One database user in compose as before; no passwords in migrations. Creating the role needs `CREATEROLE`, which the
  `POSTGRES_USER` of the official image (superuser) and the Testcontainers user have.
- Startup check: after `SET ROLE`, the role must be neither superuser nor `BYPASSRLS` (otherwise policies are ignored,
  e.g. with a misconfigured role); refuse to start otherwise.
- Platform admin operations never read tenant data across tenants (soft delete only touches `tenants`). If that is
  ever needed, it runs once per tenant through `tenantQuery`.

### 2.4 Existing data

If any tenant-owned data exists, the migration creates one tenant (`name 'Standard'`, `slug 'standard'`) and assigns
all rows to it (add the column nullable, `UPDATE`, then `SET NOT NULL` and the default). The `updated_at` triggers are
disabled during the migration so the rows keep their real modification times. On an empty database no tenant is
created. The platform admin renames the tenant afterwards.

### 2.5 Bootstrap and recovery

- On startup, if no platform admin exists, the API creates the user `admin` (platform admin, no password) and logs a
  setup link at WARN level, renewed on every start until the password is set. If the username `admin` is taken by
  someone else, it refuses to start with a clear message.
- Recovery: with `ADMIN_SETUP_LINK_FOR=<username>` set, the API logs a new setup link for that platform admin on
  startup (for a forgotten password when no other platform admin exists).
- Configurable (`auth.bootstrap`), off in tests.

---

## 3. Backend

### 3.1 Authentication — [x]

- `auth/Passwords.kt`: Argon2id via Bouncy Castle (pure Java, works on the Alpine image), OWASP parameters
  (19 MiB, 2 iterations, 1 lane), configurable so tests can use cheap ones. PHC string format, so parameters can be
  raised later. Unknown usernames, users with a pending setup and disabled users are verified against a dummy hash and
  get the same generic 401.
- **Memory:** each hash allocates 19 MiB on the 256 MB heap. A semaphore allows 2 hashes at a time; further login
  attempts fail fast with 503 instead of queuing.
- `auth/Tokens.kt`: `SecureRandom` 32-byte tokens, base64url; SHA-256 for storage.
- Throttling (in memory, single instance): per (username, client IP) a growing delay after 5 failures, and a limit
  per client IP. Throttling per username alone would let anyone keep a known username such as `admin` locked out. The
  client IP comes from the frontend (see 4.1); the API is only reachable from it, so it can trust the header.
- `configure/Security.kt`: Ktor `Authentication` with a bearer provider that resolves the session, checks expiry and
  `disabled_at`, and updates `last_used_at` at most once per hour. Principal: `UserPrincipal(userId, sessionId,
  displayName, isPlatformAdmin)`.
- `services/AuthService.kt`: login, logout, me, profile, password change, setup links. Expired sessions and setup
  tokens are deleted periodically.

Endpoints (no tenant in path):

| | | |
|---|---|---|
| `POST /auth/login` | `{username, password}` → `{token, expiresAt}` | 401 generic, 503 when hashing is busy |
| `POST /auth/logout` | ends the current session | 204 |
| `GET /auth/me` | `{id, displayName, username?, isPlatformAdmin, tenants: [{id, name, slug, role, isMember}]}` | platform admins get all tenants as `ADMIN`; `isMember` tells real memberships apart |
| `PUT /auth/me` | `{displayName}` | |
| `PUT /auth/me/password` | `{currentPassword, newPassword}` | 400 on wrong current password; ends the other sessions |
| `POST /auth/setup/inspect` | `{token}` → `{displayName, username}` for the setup page | 404 if invalid or expired |
| `POST /auth/setup` | `{token, password}` → `{token, expiresAt}` | see setup links below |

The token is always in a body, never in a path, so it doesn't end up in access or error logs.

**Setup links:** valid 7 days for new accounts, 24 hours for resets. Redeeming is one atomic statement
(`DELETE FROM setup_tokens WHERE token_hash = ? AND expires_at > now() RETURNING user_id`), rejected for disabled
users, sets the password, ends all existing sessions of the user and logs in. All setup tokens of a user are deleted
whenever their memberships, platform admin flag or disabled state change, so a link can never be used with more
privileges than the user had when it was issued.

Everything except login, setup and the OpenAPI document requires authentication (401).

### 3.2 Tenant scoping and permissions — [x]

- `TenantId` value class (`@JvmInline value class TenantId(val value: Uuid)`).
- All domain routes move into `route("/tenants/{tenantId}") { … }`. A route-scoped plugin resolves the membership (or
  platform admin), answers 404 for non-members and deleted tenants, and stores `TenantAccess(tenantId, role)` in the
  call attributes. Services receive the `TenantId` and run their transactions through `db.tenantQuery`; the queries
  themselves stay as they are (RLS filters, the default fills `tenant_id`). `References.kt` then automatically treats
  another tenant's ids as non-existent (400 "gibt es nicht").
- Permissions: `READ`, `REPORT_DAMAGE`, `EDIT`, `VIEW_MEMBERS`, `MANAGE_MEMBERS`, derived from the role in one place
  (`VIEWER`: read, report damage; `EDITOR`: + edit, view members; `ADMIN`: + manage members). The hitobito mapping
  later only has to produce a role.
- **Every route declares its permission explicitly**; there is no default. At startup the routing tree is checked and
  the application refuses to start if a route below `/tenants/{tenantId}` has no declaration. The permission matrix
  test is generated from the same declarations, so a new route can't be forgotten in either place.
- Platform routes (`/tenants` management, `/users`) live outside the tenant subtree and require the platform admin
  flag, so a tenant `ADMIN` can never reach them through tenant permissions.
- **Viewer damage reports:** with `REPORT_DAMAGE` but without `EDIT`, the service accepts only new, unresolved reports
  without resolution notes, and an `itemStatus` of at most `ACTIVE → IN_REPAIR`. (Today `NewDamageReport.itemStatus`
  can set any status, so a viewer could otherwise decommission items.) The route passes the permission into the
  service.

### 3.3 Tenants, users and members — [x]

Platform admin:

- `GET /tenants` (including deleted ones, marked), `POST /tenants`, `PUT /tenants/{tenantId}` (name, slug),
  `DELETE /tenants/{tenantId}` (soft delete), `POST /tenants/{tenantId}/restore` (409 if name or slug are taken
  meanwhile).
- `GET /users`, `PUT /users/{userId}` (display name, username, platform admin flag, disabled), `DELETE /users/{userId}`,
  `POST /users/{userId}/setup-link`, `PUT /tenants/{t}/members/{userId}` for users of other tenants.
- The last enabled platform admin can't remove their flag, be disabled or be deleted (409).

Tenant members (`VIEW_MEMBERS` to read, `MANAGE_MEMBERS` to change):

- `GET /tenants/{t}/members` → `[{userId, displayName, username, role, setupPending, loginMethod}]`
- `POST /tenants/{t}/members` `{displayName, username, role}` → creates a local user with membership, returns the
  member and a setup link token.
- `PUT /tenants/{t}/members/{userId}` `{role, displayName?, username?}`.
- `DELETE /tenants/{t}/members/{userId}` removes the membership. Users are never deleted automatically; a user without
  memberships stays (can log in, sees "no access") until the platform admin deletes them.
- `POST /tenants/{t}/members/{userId}/setup-link` → new setup link (replaces an older one; the current password keeps
  working until the link is redeemed). Only for users with a password login.

Rules:

- Tenant admins may only rename, reset or change users that are members of their tenant *only* and aren't platform
  admins; otherwise an admin of tenant A could take over an account with access to tenant B. Platform admins are never
  affected by tenant-admin actions.
- Adding an existing user (a member of another tenant) is platform-admin only, for the same reason.
- The last enabled `ADMIN` of a tenant can't be demoted or removed (409), also not indirectly by disabling or deleting
  the user. The check locks the tenant row (`FOR UPDATE`) so two admins demoting each other at once can't both
  succeed. A new tenant starts without admins until the platform admin adds one.
- Hitobito members (later) are read-only in all member endpoints.

### 3.4 Tests — [x]

- `TestSupport`: `apiTest` creates a tenant and an `EDITOR` via the services, logs in and configures the client with
  the bearer token and `/tenants/{id}/` as base path. Fixture paths become relative (`post("locations")`), since a
  leading slash replaces the base path. Helpers for further users, roles and a second tenant; `truncateAll` includes
  `tenants` and `users`; cheap Argon2 parameters, bootstrap off.
- Auth: login success/failure, the same 401 for unknown/pending/disabled users, expired and idle sessions, logout,
  password change ends other sessions, setup links (single use, expiry, invalidated by membership changes, end
  sessions), throttling, hashing semaphore, 401 without token.
- Permission matrix (generated, see 3.2): every route × `VIEWER`/`EDITOR`/`ADMIN`/platform admin/non-member/deleted
  tenant → expected status, including the viewer damage report restrictions.
- Isolation (table-driven over all resources): tenant A's data is invisible to B in lists and aggregates, by-id
  GET/PUT/DELETE give 404, references to A's ids in B's bodies give 400. Plain SQL tests: a cross-tenant reference is
  rejected by the composite foreign keys; a transaction without tenant sees no rows; the application role isn't
  superuser or `BYPASSRLS`; every table with `tenant_id` has RLS and a policy (catalog query, see 2.3).
- Member management: escalation rules, last-admin rules (including concurrency), username rules and conflicts.
- Migration: Flyway up to V1, insert data into every table, migrate to V2, check the tenant assignment, constraints
  and unchanged `updated_at`. The specific constraint error messages still appear (existing tests cover them).
- `openapi.json` updated (bearer security scheme; check the inference picks up `authenticate`, otherwise declare it in
  the OpenAPI config).

---

## 4. Frontend **(frontend)**

### 4.1 Session handling — [ ]

- `hooks.server.ts` `handle`: reads the cookie, loads `/auth/me` into `locals.user`, redirects page requests without a
  session to `/login?redirectTo=…` (except `/login`, `/setup`). Doing this in the hook rather than a layout load
  also covers `+server.ts` routes and parallel child loads.
- `/login`: form action calls `POST /auth/login`, sets the cookie (httpOnly, `SameSite=Lax`, `Secure` outside
  localhost, `path: '/'`, `maxAge` matching `expiresAt`), redirects to `redirectTo`. `redirectTo` is only accepted
  if it resolves to the same origin and starts with a single `/` (no `//host`, `/\host`, absolute URLs), otherwise
  `/`; checked in load and action, with unit tests. `/logout`: form action (POST).
- API proxy:
  - forwards only an allowlist of paths (`tenants/**`, `users/**`, `auth/me`, `auth/me/password`); login, setup and
    Swagger are not reachable through it (the token must never reach page JavaScript, and login must go through the
    rate-limited form actions);
  - adds `Authorization: Bearer <token>` from the cookie and the client IP (adapter-node `ADDRESS_HEADER` /
    `XFF_DEPTH` from nginx's header) for the API's throttling;
  - rejects unsafe methods whose `Origin` doesn't match (SvelteKit's own CSRF check only covers form content types);
  - clears the cookie on 401.
- Session expiry while editing: `mutate` shows an error toast with a login link (opening it in a new tab keeps the
  dialog's input) instead of navigating away; loads redirect to login.
- `/setup`: the link has the token in the URL fragment (`/setup#<token>`), which browsers never send to servers, so it
  isn't in nginx or Cloudflare logs or referrers. The page inspects the token, shows "Hallo <name>, dein
  Benutzername ist <username>", sets the password and logs in.
- `/account`: display name, password change, own tenants, logout.
- Headers: `Content-Security-Policy: frame-ancestors 'none'` (clickjacking of admin pages), `Referrer-Policy:
  same-origin`.

### 4.2 Tenant routes — [ ]

- All current pages move below `src/routes/[tenant]/`; the layout resolves the slug from `locals.user.tenants`
  (404 if unknown) and provides a tenant-bound API client, so call sites don't repeat the tenant id (e.g. a client
  whose paths have the `/tenants/{tenantId}` prefix stripped at type level; fall back to passing the parameter if the
  types get unwieldy).
- Reserved slugs: every top-level route name (`login`, `logout`, `setup`, `account`, `admin`, `api`, plus the old page
  names `materials`, `kits`, `loans`, `locations`, `overview`) is rejected by the backend. A frontend test compares
  the list with the top-level route directories.
- `/`: redirect to the only tenant, otherwise to the last used one (remembered in a cookie, convenience only),
  otherwise a tenant picker. No tenant at all: a "no access yet, ask your admin" page.
- Old top-level paths (`/materials/…`, `/kits`, …) redirect to the same page in the only or last used tenant.
- Removed from a tenant while using it: redirect to `/` with a toast instead of a bare 404.
- Navigation: tenant name, and a switcher if there is more than one; user menu (account, members, admin, logout).
- Role-aware UI: viewers don't see edit/delete/create actions, can report damage (without the resolve option and only
  with the "in repair" toggle). The backend enforces everything regardless.
- Damage dialog prefills "Gemeldet von" with the user's name.

### 4.3 Administration — [ ]

- `/[tenant]/members`: editors read, admins manage: list with roles and "Einrichtung ausstehend", create account
  (shows the setup link with copy button and expiry), change role / name / username, new setup link, remove.
- `/admin` (platform admins): tenants (create, rename, slug, delete, restore), users (rename, disable, platform admin
  flag, add to a tenant, setup link, delete).

---

## 5. Deployment **(deploy)** — [ ]

- Backend and frontend are deployed together (all API paths change).
- Before: tag the current images (`:pre-auth`) and take a database backup. Rollback = restore the dump and run the
  old tags; V2 can't be reverted otherwise.
- Dry run of the migration against a restored copy of the production database.
- After the first start: open the setup link from `docker compose logs material-api`, set the admin password, rename
  the `standard` tenant, create accounts.
- nginx: pass the client IP (`X-Forwarded-For`), `limit_req` for `POST` to `/login` and `/setup` per IP (a `map` on
  the method).
- vps-deployments README: replace "There is no authentication yet", document bootstrap, recovery and rollback.
- README of both repositories: roles, bootstrap, configuration.

---

## 6. Suggested order

Work on a branch `accounts` in both repositories; `main` stays deployable until step 5.

1. Schema migration with RLS, `Db.tenantQuery`, tenant scoping of the existing services and routes; isolation and
   migration tests. No authentication yet: the tenant plugin only checks that the tenant exists (and isn't deleted),
   tests create tenants directly in the database and use `/tenants/{id}/` as base path. Temporary, replaced in step 2.
2. Authentication, sessions, permissions, bootstrap; auth and permission tests.
3. Tenants, users and members endpoints with their rules.
4. Frontend 4.1 and 4.2 (the app is usable again), then 4.3.
5. Deployment, only together with the owner.

Each step ends with all tests green, the plan's status markers updated and a progress log entry in REVIEW.md. After
steps 1–3 and after step 4, an independent review (as in earlier rounds), whose findings are fixed before moving on.

---

## 6a. Notes for the implementing agent

- **Sources of truth:** this plan, the Flyway migrations (schema), the code, README.md (conventions such as the
  OpenAPI comment quirks and migration rules) and REVIEW.md (decisions and history).
- **Language:** everything users see is German: backend error and validation messages (as in `ErrorHandling.kt` and
  `api/Models.kt`), frontend texts. Code, comments and docs are English.
- **Style:** match the surrounding code (Exposed DSL, immutable data classes, KDoc on routes for the OpenAPI
  document, comment density). New dependencies only where the plan names them (Bouncy Castle, `ktor-server-auth`).
- **API contract:** after API changes `UPDATE_OPENAPI=1 ./gradlew clean test` (clean build, see README), commit
  `openapi.json`, then `bun run api:generate` in material-frontend.
- **Verification:** backend `./gradlew test` (needs Docker for Testcontainers); frontend `bun run check`, `bun run
  lint`, `bun run test`, `bun run build`, and clicking through all pages against the local stack
  (`docker compose up -d` in material-api) with each role.
- **Verify early (small spikes before building on them):** attaching permission declarations to Ktor routes and
  walking the routing tree at startup; whether the OpenAPI inference picks up `authenticate`; `SET LOCAL ROLE` and
  `set_config` through Exposed with HikariCP; RLS together with the composite foreign keys on Postgres 18. If one of
  them doesn't work as planned, stop and propose an alternative to the owner instead of silently deviating.
- **Not without the owner:** pushing images, changing the VPS or vps-deployments, merging to `main`. Commit on the
  branch per step.

---

## 7. Later: hitobito login (not now)

Waiting for the DPSG platform. The design above leaves room for it without changes to existing tables:

- **Separate accounts:** a hitobito login creates its own user, identified by `external_identities (user_id,
  provider, subject, UNIQUE (provider, subject))`, without a password login. Someone who had a local account before
  simply gets a new one; the admin deletes the old one (nothing references users).
- **Memberships of hitobito users come only from hitobito** and are replaced at every login; they are read-only in the
  UI. Local users' memberships are managed by admins only. So there is never a hitobito and a manual role for the same
  user and tenant. (If hitobito users ever need extra access that hitobito doesn't grant, revisit this then.) The
  platform admin flag can be set on either kind of account.
- `tenants.hitobito_group_id` (nullable, unique) maps a Stamm group to a tenant.
- Endpoints `GET /auth/oidc/hitobito/start` (authorization URL with state, nonce, PKCE if supported) and
  `POST /auth/oidc/hitobito/callback {code, state}` → the same `{token, expiresAt}` as the password login. The backend
  exchanges the code with the client secret, reads the profile with the `with_roles` scope, creates the user on first
  login and syncs memberships. The frontend adds a button and a callback route that sets the cookie (and the proxy
  doesn't forward these endpoints).
- A configurable mapping from hitobito role classes to `VIEWER`/`EDITOR`/`ADMIN`, and from groups (roles in groups
  below a Stamm layer count for that Stamm) to tenants. Users whose roles map to no tenant get the "no access" page.
- Role changes in hitobito take effect at the next login; possibly shorter sessions for hitobito users
  (`sessions.login_method`) or a re-sync via refresh token. Decide when the token lifetimes are known.
- Can be developed before the DPSG release against upstream hitobito or a mock OIDC server (e.g.
  `navikt/mock-oauth2-server` in Testcontainers); only the claim mapping depends on the DPSG plugin.

**Letter projects:** they may later need the same hitobito login for separating templates by organisation. Two options
then: each app integrates hitobito itself following this pattern (small per app, nothing extra to run), or a central
broker (Keycloak, Authentik, Zitadel) holds local accounts and federates hitobito for all apps. With two or three
small apps on the 4 GB VPS the first is lighter; the second becomes attractive with more apps. Both fit this design:
a broker would simply be the OIDC provider instead of hitobito.

---

## 8. Not planned (possible later)

- Hard deletion of tenants and their data.
- Sharing or lending material between tenants, Bezirk/Diözese views across tenants.
- Audit trail (`created_by` / `updated_by`), user references for `reported_by` / `borrower` (DB-6).
- Two-factor authentication, email (invites, password reset by the user), password blocklist.
