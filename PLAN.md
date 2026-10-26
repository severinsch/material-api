# Plan: exact kit allocation and pinned alternatives (FE-7, FE-8)

**Done and deployed** (FE-7, FE-8; FE-9 followed without a plan, see REVIEW.md). No open steps.

The plan for material variants and kit group amounts (previous content of this file) is done and merged to `main`; see
git history and the progress log in REVIEW.md. The owner decided (2026-10-23) to do FE-7 and FE-8 before deploying.
Both are frontend only (`material-frontend`, branch `allocation`); no API change. This plan includes the findings of an
independent review of the first draft (2026-10-23).

Status: `[ ]` open, `[x]` done, `[?]` waiting for a decision.

---

## 0. Problems

- **FE-7:** `allocate.ts` fills the groups one after the other, each in priority order, so a group can use up a
  material another group needs although it had alternatives left. Example from the demo data (stock: 8 Kohtenplanen
  Standard-Latz, 4 with 45 cm Latz, 2 Jurtenhalbdächer): if the Jurte's Dach preferred Kohtenplanen over
  Jurtenhalbdächer, 1 Jurte + 2 Kohten would report 2 Kohtenplanen missing, although 2 Jurtenhalbdächer + 8
  Kohtenplanen cover everything. Variants make this more likely: "any variant" options cover many variants, and the
  ordering heuristic ("groups with fewer variants first") only helps in simple cases.
- **FE-8:** there is no way to say "this Jurte gets the Jurtenhalbdächer, that one Kohtenplanen". The allocation
  decides alone.

---

## 1. Decisions

- **A shortage is reported only if no split of the stock covers everything.** The allocation is exact: among all
  ways to distribute the stock it picks the best by the order below, and reports what is still missing.
- **What "best" means, in this order** (each criterion only decides among results equal in the ones before):
  1. fewest missing units, summed over all groups. Units of different groups only meet where groups share a
     material; if a material counts differently in two groups, the one where a piece counts more is filled first;
  2. least waste: units packed beyond what a group needs (2 Jurtenhalbdächer = 6 units where 4 are needed waste 2).
     This keeps today's rule "don't use a large piece where smaller ones fit exactly";
  3. preferred options: fewest units (pieces × unit value) filled by options other than a group's most preferred
     ones, then fewest by options below the two most preferred priorities, and so on. Priorities are ranked densely
     per group, so options with the same priority (Kohte: Seil 20 m, 10 m and 15 m, all priority 0) count the same;
  4. earlier lines of the packing list first, then the kit's group order: if two Jurten want the 2
     Jurtenhalbdächer, the first one gets them.
  Within the variants a group treats alike (an option for any variant, or options with the same priority and unit
  value), the variant with the most stock left is taken first. Behaviour change: the Kohte gets 10 m ropes first
  (most stock) instead of following the option order.
- **The result depends on the whole list:** adding a Kohte can change which Dach an earlier Jurte gets, since that
  avoids a shortage. That's the point of FE-7; pins (FE-8) are the way to fix a choice.
- **Pins (FE-8) are per line and group:** a line can fix the option a group uses ("Dach: Jurtenhalbdach"). The group
  then uses only that option; if it isn't enough, that's a shortage of the line, named with that option only. Other
  groups and lines are allocated around it. "Automatisch" (the default) keeps all options. To pin for one of several
  identical kits, the user splits the line with the existing copy button, as for amounts.
- **Pins choose among the group's options as defined in the kit**, not among the variants of an "any variant"
  option. A group whose only option is "Seil (beliebig)" can't be pinned to "Seil 20 m" (§7).

---

## 2. FE-7: exact allocation (`src/lib/kits/allocate.ts`) — [x]

The public shape stays: `allocate(selections, stock, variants): Allocation` with `assignments` and `shortages` as
today, so `needsOf`, the needs list, the print view and the shortage texts keep working. `Allocation` gains
`approximate: boolean` (§2.4, dropped again), `KitSelection` gains optional `pins` (§3.1).

### 2.1 Demands

As today, one demand per line and group with more than 0 units (`unitsOf × quantity`). Its options are the group's
options sorted by priority (stable; the API returns them by priority, then material id, then variant id), or only
the pinned one (§3.1). Each option gets its candidate variants (its variant, or all variants of its material) and its
dense priority rank (0 for the most preferred priority; a pinned option has rank 0).

### 2.2 Interchangeable variants form classes

The search works on classes instead of variants: variants that every demand treats alike are pooled. A variant's
signature is, for every demand, the unit value and rank of the option accepting it, or nothing. (The overlap rule,
enforced by the API on kits and on merges, makes this at most one option per group.) Variants with the same signature
that some demand accepts form a class whose stock is their sum. A class can contain variants of different materials
(Kohtenplane and Kohtenblatt both with unit value 1 and the same rank in every demand); the cost only depends on unit
value and rank, and §2.6 maps pieces back to the right option. Variants no demand accepts are left out.

With the demo data, the Seil variants 10, 15 and 20 m become one class (every kit treats them alike) and 30 m another
(only the Jurte's "Seil (beliebig)" takes it).

### 2.3 Components and search

- Demands that share a class form a component (union–find); components are searched independently, in the order of
  their first demand. Most components are one demand with one class (Hering, Teleskopstange).
- Per component, a depth-first search over its demands in line and group order, memoized on the demand index and the
  stock left per class. The stock in the key is capped per class at the most pieces the remaining demands (suffix
  from the current one) could take of it, the sum of `ceil(units / unitValue)` over them, since more stock makes no
  difference. Keys are integers (mixed radix over the capped stocks) in one map per demand index.
- For a demand, the search enumerates its fills: for each of its classes, in a static order (rank, then larger unit
  value, then larger initial stock, then class index), a number of pieces from `min(left, ceil(remaining /
  unitValue))` down to 0, stopping once the demand is covered. Only dominated fills are left out (a fill beyond that
  has a piece whose removal still covers the demand). A fill's cost is its missing units, its waste and its units per
  rank; costs add up and compare lexicographically (§1). The first fill found wins ties; since the order doesn't
  depend on anything outside the memo key, that gives "earlier lines first" and "more of the preferred options"
  (criterion 4) independently of the path.
- Variants without stock still belong to classes. A demand without any class (only possible if the materials don't
  match the kits, e.g. a material deleted meanwhile) is fully missing.
- The result per demand is pieces per class, plus the missing units.

### 2.4 Budget and fallback (dropped, see 4.1)

The search counts the fills it evaluates over the whole allocation. Past a fixed budget it stops; the component being
searched and all later ones are allocated by the current greedy algorithm (kept as a private function, restricted to
their demands and the stock left), and `approximate` is set. The constant is chosen from a benchmark with the demo
kits and stock (§2.5) so the exact search covers realistic lists with room to spare while the worst case stays below
~50 ms. The packing list then shows a muted line under the shortages (or under the needs if there are none): "Sehr
große Auswahl: Die Aufteilung wurde nicht vollständig geprüft, vielleicht fehlt weniger." Unit tests for the fallback
with a deliberately large case.

### 2.5 Size

Work is roughly demands × reachable states × fills per state. The largest component in the demo data is
Seitenwände (Doppelplane 20 with unit value 2, Vierecksplane 12; 12–16 units per kit): up to 21 × 13 states and,
for a line of 3 Jurten (36 units), about 19 × 13 fills. Roof and ropes are smaller (Dach: 3 × 9 × 5 states), Fenster
similar to Seitenwände. Before fixing the budget, a benchmark test measures realistic lists (4 kits, quantities up to
4, several lines of one kit with different choices); the test asserts they are solved exactly (no fallback) and
quickly. If that fails, merging the demands of identical groups where every option has unit value 1 is the next
step (exact there, since no waste is possible).

### 2.6 From classes back to variants

In demand order, each demand's pieces of a class are taken from the class's variants with the most stock left first
(stock tracked per variant over the whole allocation), each assigned through the demand's option that accepts that
variant (for `materialId` and `anyVariant`). Missing units become missing pieces of the preferred option, or of the
pinned one, exactly as today (`variantId` null for an option over several variants, also for an option whose
material has no variants). `GroupShortage` stays as is.

### 2.7 Docs

The KDoc of `allocate` describes the criteria, the class pooling, that results depend on the whole list, and the
fallback; README (frontend) keeps its one-line pointer. REVIEW.md: FE-7 and FE-8 marked done, progress log.

---

## 3. FE-8: pinned alternatives — [x]

### 3.1 Model and URL (`packing.ts`, `allocate.ts`)

- `PackingLine` gets `pins: (string | null)[]` and `KitSelection` optional `pins?: readonly (string | null)[]`, one
  entry per group: the pinned option's id, which is its `variantId`, or its `materialId` for an option over any
  variant (both are UUIDs, unique within a group), null or missing for automatic. Ids instead of indices, since the
  option order is by priority and UUID and shifts when options are added, reprioritized or merged. Pins on groups
  with a single option, or whose option the kit no longer has, are ignored.
- **Active pins:** a pin counts only while the group's chosen amount has more than 0 units (Fenster "mit"). An
  inactive pin is kept on the line (switching back to "mit" restores it), also in the URL so a reload keeps it, but
  ignored by the allocation, by `combineLines` and by `lineLabel`. One helper, `activePins(selection)`, decides
  this.
- URL: a fourth `*` segment with the active pins as `<group index>-<option id>`, e.g. `<jurte id>*1**6-<halbdach id>`
  (no amount choices) or `<jurte id>*2*3-1*6-<id>`. `serializeSelection` builds the segments explicitly (today's
  `.filter(Boolean)` would drop an empty choices segment and turn the pin into a choice), trailing empty segments are
  dropped. Old links keep working; `parseSelection` ignores pins it can't apply, like choices.
- `newLine` starts without pins; `addCopy` copies them; `combineLines` combines only lines with the same choices and
  active pins; `addKit` adds to a line with default choices and no active pins.
- `lineLabel` names active pins too, by `plainName` ("Seil", not "Seil (beliebig)", avoiding nested parentheses):
  `Jurte (Mittelstange: Einbein, Dach: Jurtenhalbdach)`; a group with several amounts and a pin is one entry,
  `Jurte (Fenster: mit · Fensterplane)`.
- `describeShortage` takes the line's active pins and lists only the pinned option for a pinned group, including the
  single-option wording ("Jurte (Dach: Jurtenhalbdach) – Dach: 1× Jurtenhalbdach fehlt").

### 3.2 Packing list UI (`[tenant]/kits/+page.svelte`)

- A line of a kit with at least one group with several options shows, next to the amount selects, a ghost button
  "Alternativen festlegen". It reveals one select per such group whose chosen amount isn't 0: trigger
  `Dach: automatisch` or `Dach: Jurtenhalbdach` (label by `groupLabel`), items "Automatisch" and the options by
  priority, named with `optionName` ("Seil (beliebig)", "Kohtenplane 45 cm Latz"), each with "· N verfügbar". A line
  starts revealed if it has a pin (also after a reload) and never collapses by itself, so setting a select back to
  "Automatisch" doesn't make it vanish. The reveal state is per line on the page, not in the URL.
- The copy button is shown for lines with amount choices or alternatives; its text becomes "Weitere mit anderer
  Auswahl", aria "{kit} noch einmal mit anderer Auswahl hinzufügen" (gender-neutral).
- Kit card footer: today a kit without amount choices shows the quantity buttons of its first line, assuming one
  line. Now the quantity buttons show only if the kit has exactly one line with default choices and no active pins;
  otherwise the summary with "+" (adds an unpinned line) and "3 auf der Packliste · in der Packliste anpassen".
- The summary, copied text and printed list use the labels with pins (§3.1).

---

## 4. Tests — [x]

- `allocate.test.ts`:
  - the FE-7 example (Jurte Dach preferring Kohtenplanen, 1 Jurte + 2 Kohten, demo stock): no shortage;
  - a case where the greedy order fails across three groups;
  - the existing cases keep passing, updated where the behaviour change of §1 applies;
  - criteria order: fewer missing beats waste, less waste beats priority, priority beats line order; equal priorities
    count the same; ties go to the earlier line;
  - classes: variants of different materials in one class come back as the right options; the split takes the
    variant with the most stock first; variants no demand accepts;
  - pins: a pinned group uses only that option, a shortage on a pinned group, a pinned option without stock, other
    groups work around a pin, unknown pins and pins on single-option groups are ignored, an inactive pin (amount 0);
  - edge cases: an option whose material has no variants (fully missing, `variantId` null, `anyVariant` false), "any
    variant" of a material with a single variant;
  - budget: a case past the budget gives the greedy result with `approximate`; the benchmark of §2.5 is solved
    exactly;
  - a randomized comparison against a brute-force reference on small instances (a few groups, 2–3 options, small
    stock, unit values 1–3), with a fixed seed: same missing units, waste and rank costs.
- `packing.test.ts`: URL round trip with pins, with pins but no choices, old URLs, invalid pins, inactive pins;
  `combineLines` and `lineLabel` with pins; `describeShortage` of a pinned group (several and single options).

### 4.1 Changes after the implementation

- If every demand's best fill on its own fits the stock, that's the result (the same the search would find);
  otherwise their costs bound the search, which skips fills that can't beat the best found.
- The owner decided (2026-10-23) against the budget and the greedy fallback (§2.4): realistic lists (under 50 pieces
  per Plane type) take milliseconds, so the fallback, `approximate` and its note were removed again.

## 5. Order and verification

1. FE-7 in `allocate.ts` with tests (pins already in `KitSelection`, so the algorithm is written once).
2. FE-8 model, URL, labels, then the UI.
3. Independent review of the implementation, fixes, progress log in REVIEW.md, plan status.

Verification: `bun run check`, `bun run lint`, `bun run test`, `bun run build`; in the browser on the local stack
(restored dump): the FE-7 case with the Jurte Dach temporarily preferring Kohtenplanen, pins on Dach and Fenster,
reload from the URL, copy and print markup, phone width.

## 6. Notes for the implementing agent

- Style: match the surrounding code (Svelte 5 runes, existing components, comments explaining why). No new
  dependencies.
- User-facing texts German, code and docs English.
- Not without the owner: deploying, touching the live instance.

## 7. Not planned (possible later)

- Pinning a variant of an "any variant" option ("this Kohte gets the 20 m ropes").
- Pinning a mix ("1 Jurtenhalbdach + 3 Kohtenplanen") or pinning per kit instead of per line.
- Showing in the select what "Automatisch" currently picks for the line.
