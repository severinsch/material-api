# Plan: material variants and kit group amounts (Q8, Q9)

The accounts plan (previous content of this file) is done and deployed; see git history and the progress log in
REVIEW.md. This plan answers the open domain questions Q8 and Q9 (REVIEW.md) and prepares FE-7/FE-8, which come
next and build on it. It includes the findings of an independent review of the first draft (2026-10-22).

Covers both repositories; frontend work is marked **(frontend)**.

Status: `[ ]` open, `[x]` done, `[?]` waiting for a decision.

---

## 0. Problems

- **Q8:** a Jurte is built with an Einbein (1 Jurtenstange) or a Dreibein (3). A group has exactly one amount, so the
  kit has to pick one, or there are two kits. Optional groups ("with or without windows") are the same problem with
  the amounts 0 and 12, solved by a special flag.
- **Q9:** ropes of 10, 15 and 20 m are three materials. Kits list all of them as equal options, every new length has
  to be added to every kit, and the material list shows five rope rows where people think of "Seil". The same goes for
  Kanister (10 L, 20 L) and Kohtenplanen with and without a 45 cm Latz, which are labeled K01… and K45 01….

---

## 1. Decisions

Decided with the owner (2026-10-22):

- **Q8: discrete amounts per group** ("Ausführungen"), not a min/max range. A group has one or more amounts, each with
  a label, e.g. Mittelstange: Einbein = 1, Dreibein = 3. The packing list picks one per kit. All amounts of a group
  share the group's material options. `is_optional` is replaced: an optional group is a group with an amount of 0
  ("ohne" = 0, "mit" = 12).
- **The packing list must hold the same kit several times with different choices** (2× Jurte with Einbein, 1× Jurte
  with Dreibein).
- **Q9: every material has variants** (V2 in the discussion). `materials` keeps what all variants share (name,
  category, description, tracking type); the new `material_variants` holds what differs, and everything with stock
  moves to the variant: bulk stock, individual items, purchases, loan lines. Most materials have exactly one,
  unnamed variant, and the UI doesn't show variants for them.
- **Variants of a material share the tracking type**; no mixing of bulk and individual variants.
- **Identifiers of individual items are unique per variant**, not per material (K01 for the plain Kohtenplanen and
  K45 01 for the ones with Latz; the same identifier in two variants is allowed).
- **A kit option refers to a material (any variant) or to one specific variant.** No "minimum length" feature: a kit
  that needs at least 15 m lists the variants 15 m and 20 m.

Defaults chosen in this plan (cheap to change):

- **UI names:** material variants are "Varianten"; the amounts of a kit group are "Ausführungen". Code: `variants` /
  `MaterialVariant`, and `amounts` / `KitGroupAmount`, so the two never share a name.
- **Display name of a variant:** material name and variant name separated by a space, `Seil 15 m`,
  `Kohtenplane 45 cm Latz`; just `Seil` for a material with one unnamed variant. No parentheses, since many places
  already append parentheses (group names, identifiers, places). Items: `Kohtenplane 45 cm Latz K45 01` if the
  material has several variants, otherwise `Kohtenplane K01` as today. One helper per repository builds these names,
  so the format can change in one place.
- **Variant names:** a material with one variant may leave it unnamed; with two or more, every variant needs a
  non-blank name, unique within the material. **Variants are sorted by name** (numeric collation: 10 m, 15 m, 20 m);
  there is no manual order, so order never carries hidden meaning.
- **Bulk storage location is per variant** (it lives in `bulk_stock`, which moves to the variant). The material
  dialog shows one location for all variants by default and a switch "Lagerort je Variante", which is on
  automatically when the variants' locations differ.
- **Loans lend specific variants.** Whoever lends ropes knows which lengths left; "3 Seile, any length" isn't a loan
  line. The rule "a quantity or specific items, not both" applies per variant.
- **A material option in a kit includes variants added later.** Adding the variant "45 cm Latz" to Kohtenplane makes
  every kit that uses "Kohtenplane" accept them, which is the point of the feature; a kit that must not accept them
  switches to the specific variant. Since "any variant" shares one unit value, materials whose variants count
  differently (Kanister 10 L / 20 L in a "Wasser" group) list their variants individually; the kit editor says so.
- **Merging materials** (section 4.4) turns existing separate materials (Seil 10 m, Seil 15 m, Seil 20 m) into
  variants of one material in one step, keeping items, purchases, loans and kit references with their exact meaning.
  Exception by design: if the target already has several variants and a kit uses it with "any variant", that kit
  accepts the merged-in variants too ("any" includes variants added later); the merge dialog says so per kit.
- **Moving an individual item to another variant of its material** is allowed (fixing a wrong entry). Like a status
  change, it isn't checked against quantity loans of the old variant; those may then exceed what's available, which
  the loan dialog already tolerates for unchanged lines.

---

## 2. Q8: kit group amounts

### 2.1 Schema (migration `V4__kit_group_amounts.sql`) — [x]

```sql
-- The amounts a group can be packed with, e.g. Einbein = 1 and Dreibein = 3 Jurtenstangen, or "ohne" = 0 and
-- "mit" = 12 Fensterplanen. The packing list picks one per kit; the first one is the default.
CREATE TABLE kit_group_amounts (
    group_id       UUID         NOT NULL,
    tenant_id      UUID         NOT NULL DEFAULT current_tenant_id(),
    position       INTEGER      NOT NULL,
    label          VARCHAR(100),             -- NULL only for a group's single amount (checked by the API)
    required_units INTEGER      NOT NULL CHECK (required_units >= 0),
    PRIMARY KEY (group_id, position),
    CONSTRAINT kit_group_amounts_group_id_fkey FOREIGN KEY (group_id, tenant_id)
        REFERENCES kit_groups (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT kit_group_amounts_label_key UNIQUE (group_id, label)
);
```

- RLS enabled with the `tenant_isolation` policy (README convention; `TenantIsolationTest` checks it).
- Existing groups: a non-optional group gets one amount (`NULL`, `required_units`); an optional group gets
  `('ohne', 0)` at position 0 and `('mit', required_units)` at position 1, so it stays excluded by default as today.
  **The INSERT copies `tenant_id` from `kit_groups`**: `current_tenant_id()` is NULL while Flyway runs, so relying on
  the default fails on live data while empty-database tests pass.
- Then drop `kit_groups.required_units` and `kit_groups.is_optional` (no row updates, so no trigger handling needed).
- `MigrationTest`: groups with and without `is_optional` at V3, migrate, check the amounts and tenant ids.

### 2.2 API — [x]

```kotlin
data class KitGroup(val name: String?, val amounts: List<KitGroupAmount>, val options: List<KitGroupOption>)
data class KitGroupAmount(val label: String?, val requiredUnits: Int)
```

`NewKitGroup.amounts: List<NewKitGroupAmount>` replaces `requiredUnits`/`isOptional`. Validation (German messages):
at least one amount; at least one amount above 0; with two or more amounts every label is non-blank and unique; labels
at most 100 characters; `requiredUnits >= 0`. Breaking change; backend and frontend are deployed together.

### 2.3 Packing list **(frontend)** — [x]

- **Lines instead of kits:** the packing list is a list of lines, each `kit + quantity + chosen amount per group`
  (index into `amounts`, default 0). The same kit can appear in several lines. FE-8 later adds pinned options to the
  line.
- **Kit cards:** groups with one amount look as today. A group with exactly two amounts where one is 0 keeps today's
  look ("12× Fensterplane", badge "optional"). Other groups list their amounts with the default marked
  ("Mittelstange: Einbein 1× (Standard) · Dreibein 3× Jurtenstange"). Choices are no longer made on the card, since a
  card can stand for several lines.
  - Kits without choices: the footer keeps the quantity buttons.
  - Kits with choices: "Zur Packliste" adds a line with the default choices (or increments an existing line with
    default choices); the footer shows how many are on the list and "Ausführung in der Packliste wählen" (on phones
    the list is below all kits).
- **Lines in "Ausgewählte Sets":** quantity buttons, remove, and one select per group with several amounts
  ("Mittelstange: Einbein ▾", "Fenster: ohne ▾"). An action "Weitere mit anderer Ausführung" adds a copy of the line
  with quantity 1. Identical lines are allowed while editing (merging while the user changes a select would make
  lines jump), but the summary, copied text and print combine them ("3× Jurte (Einbein)").
- **Line label** for summary, copy, print and shortages: `Jurte (Mittelstange: Einbein, Fenster: mit)`, listing only
  groups with several amounts.
- **URL format:** `kit=<kit id>*<quantity>[*<group>-<amount>.<group>-<amount>…]`, listing only non-default choices,
  one `kit` parameter per line in order. No compatibility with the old format (no links were ever shared); old values
  are simply ignored by the parser like any malformed value.
- **Allocation** (`allocate.ts`): a selection carries the amount index per group; units are
  `amounts[choice].requiredUnits * quantity`, groups with 0 units are skipped. The allocator returns assignments
  `(line, group, variant, pieces)` (in step 1 still per material) plus shortages per line and group; needs and the
  per-kit breakdown are derived from them, so FE-7 and FE-8 can build on the same output. "14× für Jurte (Einbein)"
  stays correct with two Jurte lines.

### 2.4 Kit editor **(frontend)** — [x]

- The "Einheiten" field becomes an "Ausführungen" list. With one amount it looks as today (just the units field, no
  label). "Ausführung hinzufügen" adds rows of label + units; then all rows show a label field, and the help text
  says that the first one is the default in the packing list.
- The "Optional" checkbox stays as a shortcut: checking it inserts `ohne / 0` as the first amount (prefilling "mit"
  as the label of the existing amount if it had none); unchecking removes the 0 amount. It is checked iff the group
  has an amount of 0.

---

## 3. Q9: material variants — schema (migration `V5__material_variants.sql`) — [x]

### 3.1 New table

```sql
-- What differs between otherwise equal materials, e.g. the lengths of a rope or Kohtenplanen with and without Latz.
-- Every material has at least one variant; stock, items, purchases and loans belong to a variant. A material with
-- a single variant usually leaves it unnamed, and the UI doesn't show variants for it.
CREATE TABLE material_variants (
    id            UUID PRIMARY KEY,
    tenant_id     UUID                   NOT NULL DEFAULT current_tenant_id(),
    material_id   UUID                   NOT NULL,
    tracking_type material_tracking_type NOT NULL,
    name          VARCHAR(100),          -- NULL only for a material's single variant (checked by the API)
    created_at, updated_at,
    CONSTRAINT material_variants_material_id_fkey FOREIGN KEY (material_id, tenant_id, tracking_type)
        REFERENCES materials (id, tenant_id, tracking_type) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT material_variants_name_key UNIQUE (material_id, name),
    CONSTRAINT material_variants_id_tenant_id_key UNIQUE (id, tenant_id),
    CONSTRAINT material_variants_id_tenant_id_tracking_type_key UNIQUE (id, tenant_id, tracking_type),
    CONSTRAINT material_variants_id_material_id_tenant_id_key UNIQUE (id, material_id, tenant_id)
);
```

- `tracking_type` is repeated on the variant so the composite keys keep bulk stock on bulk variants and items on
  individual variants (the technique already used between `materials` and `bulk_stock`/`individual_items`), and all
  variants of a material share the material's type.
- `(id, material_id, tenant_id)` is the target of the kit option key (3.2), so a merge that moves a variant to another
  material carries its kit options along (`ON UPDATE CASCADE`).
- RLS policy and `updated_at` trigger. Lookups by `material_id` use `material_variants_name_key`, whose first column
  it is; no extra index.

### 3.2 Existing tables

| Table | Before | After |
|---|---|---|
| `bulk_stock` | PK `material_id` | PK `variant_id`, `UNIQUE (variant_id, tenant_id)` (target for purchases), FK `(variant_id, tenant_id, tracking_type)` → variants `ON DELETE CASCADE`; location FK unchanged incl. `ON DELETE SET NULL (storage_location_id)` |
| `individual_items` | `material_id`, `UNIQUE (material_id, identifier)` | `variant_id`, `UNIQUE (variant_id, identifier)`, FK `(variant_id, tenant_id, tracking_type)` → variants, `RESTRICT` |
| `purchases` | `material_id` → `bulk_stock` | `variant_id` → `bulk_stock (variant_id, tenant_id)`, `RESTRICT` |
| `loan_lines` | `material_id` + `quantity` | `variant_id` + `quantity` (CHECK updated), FK `(variant_id, tenant_id)` → variants, `RESTRICT` |
| `kit_group_options` | `material_id`, PK `(group_id, material_id)` | `material_id NOT NULL` plus nullable `variant_id` (NULL = any variant); FK `(variant_id, material_id, tenant_id)` → `material_variants (id, material_id, tenant_id)` `ON UPDATE CASCADE ON DELETE RESTRICT`; key `UNIQUE NULLS NOT DISTINCT (group_id, material_id, variant_id)` instead of the PK |

- Constraint names follow the existing pattern (`individual_items_variant_id_identifier_key`,
  `purchases_variant_id_fkey`, `loan_lines_variant_id_fkey`, `kit_group_options_variant_id_fkey`, …) and
  `constraintMessages` in `ErrorHandling.kt` is updated, including messages for deleting a variant or a material
  whose variants are still referenced. `MigrationTest` checks that every name in `constraintMessages` exists.
- Indexes for foreign key columns no unique constraint covers (`loan_lines.variant_id`, `purchases.variant_id`,
  `kit_group_options (variant_id, material_id)`).

### 3.3 Data migration

1. One variant per material: `gen_random_uuid()` as id (deliberately not the material's id: if both were equal, code
   that mixes up material and variant ids would pass every test on migrated data), `name NULL`, **`tenant_id` and
   `tracking_type` copied from the material** (see 2.1 on `current_tenant_id()` during migrations).
2. Fill the new `variant_id` columns through the material → variant mapping, set `NOT NULL` where applicable, drop
   the old columns and constraints, add the new ones.
3. Existing kit options keep `material_id` with `variant_id NULL` (with a single variant, "any variant" means the same
   as before).
4. The `updated_at` triggers of the updated tables (`individual_items`, `bulk_stock`, `purchases`) are disabled by
   name during the migration and re-enabled afterwards (not `DISABLE TRIGGER ALL`, which would also disable the
   foreign key checks).

`MigrationTest`: the existing V1 → latest tests keep passing; new: data in every touched table at V4, migrate to V5,
check that every row kept its material (via its variant) and tenant, counts and identifiers are unchanged,
`updated_at` unchanged, deleting a location still only nulls the reference; an empty database migrates too.

**Dry run on production data:** the owner synced a production dump (test data only, nothing real) to
`synced-backup/scout_material-2026-10-22.dump` (git-ignored locally via `.git/info/exclude`, never commit it).
Restore it into a local container and run V4 and V5 against it, comparing per-table row counts and material counts
before and after. Losing this data wouldn't matter (the owner could re-enter it), but it is the best realistic
test data there is, and the owner wants to keep it on production.

---

## 4. Q9: backend — [x]

### 4.1 Models — [x]

```kotlin
sealed interface Material {          // counts are sums over the variants
    val id; val name; val category; val description
    val availableCount; val damagedCount; val loanedCount
}
data class BulkMaterial(…, val variants: List<BulkVariant>)              // loses location/locationDetail
data class IndividualMaterial(…, val variants: List<IndividualVariant>)
// Plain classes (no sealed interface): the lists are concretely typed, which keeps the OpenAPI schema simple.
data class BulkVariant(val id, val name: String?, val availableCount, val damagedCount, val loanedCount,
    val location: StorageLocation?, val locationDetail: String?)
data class IndividualVariant(val id, val name: String?, val availableCount, val damagedCount, val loanedCount)

sealed interface NewMaterial { name; category; description }
data class NewBulkMaterial(…, val variants: List<NewBulkVariant>, val removedVariantIds: List<Uuid> = emptyList())
data class NewBulkVariant(val id: Uuid? = null, val name: String? = null, val availableCount: Int,
    val damagedCount: Int = 0, val storageLocationId: Uuid? = null, val locationDetail: String? = null,
    val expectedAvailableCount: Int? = null)
data class NewIndividualMaterial(…, val variants: List<NewIndividualVariant> = listOf(NewIndividualVariant()),
    val removedVariantIds: List<Uuid> = emptyList())
data class NewIndividualVariant(val id: Uuid? = null, val name: String? = null)

data class IndividualItem(…, val materialId: Uuid, val variantId: Uuid, …)  // materialId stays, read-only, for grouping
data class NewIndividualItem(val variantId: Uuid, …)                        // replaces materialId
data class Purchase(…, val materialId: Uuid, val variantId: Uuid, …); NewPurchase(val variantId: Uuid, …)
data class LoanQuantity(val variantId: Uuid, val quantity: Int)
data class KitGroupOption(val materialId: Uuid, val variantId: Uuid?, val unitValue: Int, val priority: Int)
```

- **Variant list on writes:** `PUT /materials/{id}` takes the full variant list: entries with `id` update that
  variant, entries without `id` are created, and variants in `removedVariantIds` are deleted (409 with a specific
  message if they have items, purchases, loans or kit references). An existing variant that is neither listed nor
  removed gives 409 "Das Material wurde inzwischen geändert. Lade die Seite neu.", so a variant another editor just
  added is never deleted unnoticed (the same idea as `expectedAvailableCount`). `id`s on create, or ids of another
  material's variants, are rejected (400). One request keeps the dialog atomic.
- **Validation:** at least one variant; names as in section 1; `expectedAvailableCount` per bulk variant (409 as
  today if the stock changed meanwhile, e.g. by a purchase).
- **Kit options:** `variantId`, if given, must belong to `materialId` (400). Within a group, a material appears
  either as one "any variant" option or as options for distinct specific variants (15 m and 20 m for "at least
  15 m"), not both (400, "überschneiden sich").
- **Items:** `variantId` may change on update, only to another variant of the same material (section 1); the
  identifier must be free in the target variant. Changing the material stays forbidden.
- **Loans:** "a quantity or specific items, not both" per variant.
- Query parameters stay material based (`GET /items?materialId=`, `GET /purchases?materialId=` return those of all
  variants); no `variantId` filters unless the frontend needs them.

### 4.2 Services — [x]

- `Availability.kt` works per variant: `availableCounts`, `loanedCounts`, `lockStock` take variant ids; locks are taken
  on `material_variants` rows in id order. Material counts are sums. `materialName` becomes the variant display name.
- `MaterialService`: create/update with the variant list (bulk stock row per bulk variant), counts per variant,
  `merge` (4.5).
- `ItemService` (sorted by variant name, then identifier), `PurchaseService`, `LoanService` (`checkLines`, `pieces`,
  `itemName` per variant), `KitService` (options sorted by priority, material, variant; amounts), `References.kt`
  (`requireVariant(id, type)`): switch to variants. Messages use the display name helper.
- `Tables.kt`: `MaterialVariants`, changed columns, `KitGroupAmounts`.

### 4.3 Docs — [x]

README: the "Kit" section (amounts, options referring to a material or a variant, one unit value for "any variant")
and "Hybrid Tracking" (variants; items unique per variant). REVIEW.md: Q8/Q9 answered with a pointer to this plan;
progress log entries.

### 4.4 Merging materials — [x]

`POST /materials/merge` (EDIT) with `{ materialIds: [<target>, <source>…], name, variantNames: { <variantId>: "<name>" } }`:

- The first material is the target and keeps its id, category and description; it gets `name`. The variants of all
  other materials move to it, and those materials are deleted. Items, stock, purchases and loans point to variants
  and come along unchanged; kit options pointing to a specific variant follow through the cascading key (3.1).
- **Kit options keep their exact meaning:** before moving, every option with `variant_id NULL` on a material that has
  a single variant is rewritten to that variant, for the target as well as the sources ("Seil 10 m" meant exactly
  that rope; after the merge it must not mean "any Seil"). A material with several variants already means "any
  variant, including ones added later", so its "any" options stay; if that material is a *source*, such options
  can't be expressed afterwards and the merge is rejected (409, naming the kits).
- All materials must have the same tracking type (400). After the merge every variant needs a unique name, taken from
  `variantNames` or its current name (400 otherwise).
- One transaction; locks all involved materials' variant rows.
- The response is the merged material. The frontend shows the affected kits in the preview (computed from the kits it
  already loaded).

### 4.5 Tests — [x]

- Materials: variant create/update/remove, the "neither listed nor removed" conflict, name rules, deleting referenced
  variants (409 messages), `expectedAvailableCount` per variant, counts per variant and summed, foreign variant ids
  rejected.
- Items: identifiers unique per variant (same identifier in two variants allowed), moving an item between variants
  (counts follow).
- Purchases and loans: per variant stock and availability, the quantity-or-items rule per variant, messages with
  variant names.
- Kits: amounts validation and round trip; options by material or variant; the overlap rule; variant of another
  material rejected.
- Merge: data moves, single-variant "any" options rewritten for target and sources, multi-variant target keeps "any",
  multi-variant source with "any" options rejected, every other 400/409 case.
- `MigrationTest` for V4 and V5, `TenantIsolationTest` covers the new tables (catalog test) plus cross-tenant
  variant references in bodies, `PermissionTest` covers the merge route (generated), `OpenApiTest` with the updated
  `openapi.json`.

---

## 5. Q9: frontend **(frontend)** — [x]

### 5.1 Hierarchy and the confusion with items

The material list today expands individual materials into their items. With variants there are two levels below a
material, and they must not look alike. Rule: **variants are a breakdown of the counts, items are the physical
things.**

- **Material list:** one row per material with summed counts. A material with several variants shows a muted line
  under its name: `10 m: 4 · 15 m: 6 · 20 m: 2` (available counts). The expand chevron keeps its meaning "show items"
  and stays on individual materials only. Search matches variant names. Location column: the distinct locations of
  the variants (bulk) or items (individual), as today.
- **Item table** (list expansion and material page): with several variants, grouped by variant with a heading row per
  variant (`45 cm Latz · 8 verfügbar`), so the hierarchy reads Material → Variante → Gegenstände. With one variant,
  unchanged.
- **Material page:** with several variants a "Varianten" table (name, Bestand, Ausgeliehen, Verfügbar, Beschädigt,
  and for bulk materials Lagerort) below the summed count tiles. Purchases get a "Variante" column. Damage reports and
  other item names use the display name helper.

### 5.2 Editing

- **Material dialog:** below name/category/description a "Varianten" section.
  - One variant: looks like today's dialog (bulk: stock, damaged, location; individual: nothing), plus a link
    "Varianten anlegen" that switches to the list.
  - Several variants: one row (card on phones) per variant: name, and for bulk stock and damaged. Location: one
    shared Lagerort and Platz by default, switch "Lagerort je Variante" (section 1).
  - Switching from one to several variants asks for a name for the existing variant too.
  - Removing a variant with stock asks for confirmation ("Variante 15 m mit 4 Stück entfernen?") and sends it in
    `removedVariantIds`; the API's 409 for referenced variants is shown as usual.
- **Items:** the add-items dialog has a variant select (preselected when opened from a variant's heading); the edit
  dialog allows moving an item to another variant. `lib/identifiers.ts` suggests the next free identifier per variant.
- **Purchases:** the purchase dialog has a variant select if there are several.
- **Merge:** in the material list, editors select materials (checkboxes appear in a selection mode) and choose
  "Zusammenführen". The dialog: target (first selected, changeable), name prefilled with the common prefix
  ("Seil 10 m", "Seil 15 m", "Seil 20 m" → "Seil"), variant names prefilled with the remainders ("10 m", …), a
  preview of the result, and the kits that use the materials with a note that they keep using exactly the same
  variants. Materials of different tracking types can't be selected together.

### 5.3 Loans

The loan dialog lists variants as separate rows (`Seil 15 m`), grouped under the material if it has several.
`picking` and `quantities` are keyed by variant; picking items works per variant. `loanContents` (and
`loans.test.ts`) and the loans and overview pages (item names and links, `overview/+page.svelte`) use the display
names.

### 5.4 Locations

`routes/[tenant]/locations/[id]` lists bulk materials by their variants' locations: a variant stored there appears
as `Seil 15 m`; the load filters on variants instead of `material.location`.

### 5.5 Kits and packing list

- **Kit editor:** the material picker offers, for a material with several variants, "Seil (beliebige Variante)"
  followed by the single variants (indented, searchable by "Seil 15"). Materials with one variant appear once; an
  option that points to a specific variant of a material that has only that variant left still renders (as the
  material). `groupError` mirrors the API's overlap rule. Help text: "beliebige Variante" counts every variant with
  the same units; list variants individually if they count differently.
- **Kit cards:** an option for a material with several variants reads `6× Seil (beliebig)`, availability summed.
- **Allocation:** stock is per variant. A material option is expanded into its variants (same unit value and
  priority), taking the variant with the most remaining stock first. Groups whose options cover fewer variants are
  allocated first (generalizing today's "single option first"), so a specific "Seil 20 m" isn't used up by a group
  that takes any rope. The output stays the assignments of 2.3, now per variant, plus whether the piece was assigned
  through an "any" option.
- **Needs** are keyed by material and variant-or-any:
  - pieces for a specific variant: `2× Seil 20 m`, available = that variant's stock;
  - pieces through an "any" option: `4× Seil (beliebig)`, available = the material's stock minus what the specific
    lines of that material take, with the places of the variants used. If both exist, the specific lines are listed
    directly before the "beliebig" line, which says what it excludes: `4× Seil (beliebig, außer den 2× 20 m)`.
  - `placeOf` works per variant; `describeShortage` names variants and "beliebig" options.
- **Expect more FE-7 misses:** expanded options make groups overlap more often, so the greedy allocator's misses
  become more common. FE-7 comes after this plan (owner's decision).

### 5.6 Shared helpers and tests

- `format.ts`: `variantName(material, variant)` and `itemName(material, variant, item)`; the lookups the pages build
  today (`materials.get(id)`) get a variant counterpart (variant id → material and variant).
- Unit tests: `allocate.test.ts` (amount choices, several lines of one kit, "any" over variants, specific before any,
  any and specific of the same material in the needs), `packing.test.ts` (new URL format, combined lines
  in the summary), `loans.test.ts`, name helpers.

---

## 6. Order

Two branches, `kit-amounts` and `variants` (based on it). The owner decided (2026-10-22) to deploy everything
together once done and tested locally; FE-7 and the other open items wait until afterwards.

1. **Q8** on branch `kit-amounts` in both repositories: V4, API, tests; frontend 2.3 and 2.4 (allocator output
   already as assignments). Commit per repository.
2. **Q9 schema and backend** on branch `variants` (based on `kit-amounts`): 3, 4.1–4.3, 4.5 without merge. The frontend
   doesn't compile against the new API until step 3, which is fine on the branch.
3. **Q9 frontend** (5 without merge).
4. **Merge** backend (4.4) and frontend (5.2).
5. Independent review of everything, fixes, progress log.
6. Deployment, only together with the owner: dry run on the synced dump first (3.3), backend and frontend
   together, backup before (V4 and V5 can't be reverted otherwise); afterwards the owner merges the demo rope and
   Kohtenplane materials through the UI (also worth trying locally on the restored dump before).

Each step ends with all tests green, the status markers updated and a progress log entry in REVIEW.md.

## 7. Notes for the implementing agent

- **Sources of truth:** this plan, the migrations, the code, README.md (conventions: tenant-owned tables, OpenAPI
  comment quirks, migration rules) and REVIEW.md.
- **Language:** user-facing texts German (API messages, frontend), code and docs English.
- **Style:** match the surrounding code (Exposed DSL, validation in model init blocks, KDoc on routes, Svelte 5
  runes, existing components). No new dependencies.
- **API contract:** `UPDATE_OPENAPI=1 ./gradlew clean test`, commit `openapi.json`, then `bun run api:generate` in
  material-frontend.
- **Verification:** backend `./gradlew test`; frontend `bun run check`, `bun run lint`, `bun run test`, `bun run
  build`, and the flows against a local stack (`docker compose up -d` here) with a viewer and an editor: material
  with variants (bulk and individual), items per variant, purchase, loan, kit with amounts and "any variant" options,
  packing list with two lines of one kit, merge. Report what could not be clicked through in a browser.
- **Not without the owner:** merging to `main`, deploying, touching the live instance's data or its backups.

## 8. Not planned (possible later)

- FE-7 (exact allocation) and FE-8 (pinning an option per kit), next.
- Splitting a variant off into its own material (the reverse of merging).
- A manual order of variants; numeric variant attributes (length, volume) and "at least 15 m" options.
- "Any variant" lines in loans.
