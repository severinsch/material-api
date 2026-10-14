# Plan: usability round

Based on the UI/UX review of 2026-10-13 (see REVIEW.md for the earlier reviews). Covers both repositories; most work
is in material-frontend, backend changes are marked **(backend)**.

Status: `[ ]` open, `[x]` done, `[?]` waiting for a decision.

---

## 1. Problem fixes

### P1 One meaning of "available" — [x]

The materials list respects the "count lent out pieces as unavailable" switch, the material page always shows
`availableCount` including lent pieces (e.g. Hering: 70 in the list, 120 on the detail page).

- Use `availableNow()` (or an explicit breakdown) on every page that shows availability: material page, kit cards,
  packing list, loan dialog.
- On the material page show the full breakdown, e.g. "120 in stock · 70 available · 50 lent out · 0 damaged", so the
  numbers explain each other.

### P2 Readable kit alternatives and shortages — [x]

- Kit cards: show alternatives as "2× Jurtenhalbdach **or** 6× Kohtenblatt", label the preferred one in words
  ("preferred") instead of an arrow that looks like an expand button.
- Packing list: report shortages per part, e.g. "Dach: 2 units short (1 Jurtenhalbdach or 2 Kohtenblatt)", instead of
  only "1 Jurtenhalbdach missing". Needs `allocate()` to return missing units per group in addition to the material
  totals (with tests).

### P3 Phone use — [x]

- Loans: show lent / due date and the overdue badge below the borrower on small screens.
- Kits: packing list reachable without scrolling past all kits (sticky "Packing list (n)" bar that jumps to / opens
  the list, or list first on small screens).
- Navigation: labels visible (or a labelled bottom bar) on small screens, not only icons.
- Item table: no sideways scrolling that hides the actions; stacked layout or an actions menu on small screens.
- Touch targets of at least 44 px for row actions on touch devices; don't place Edit directly next to Delete.

### P4 Deleting things that are still in use — [x]

Deleting an item with damage reports or loans asks for confirmation and then fails with 409.

- **(backend)** Expose whether an item has history (e.g. `hasHistory` on `IndividualItem`), or a similar flag where
  deletes are restricted.
- Frontend: disable Delete for such items with the hint "Has history – set the status to Decommissioned instead",
  ideally with a one-click "Decommission".

### P5 Error messages, dates and prices — [x]

- **(backend)** Validation messages in plain words instead of field names ("The due date can't be before the lent
  date" instead of "dueAt must not be before lentAt"); review all `require(...)` messages in `api/Models.kt`.
- Frontend wording consistent with the UI ("Choose a material for every alternative", not "option"); duplicate-name
  conflicts should say what clashed.
- Show form errors next to the field where it's cheap (client-side checks), toasts for the rest.
- `<html lang="de">` (or matching the chosen UI language) so date pickers show dd.mm.yyyy.
- Prices formatted as currency ("0,35 €").

### P6 "Returned today" — [x]

Single click on an ambiguous icon, no confirmation, no undo.

- Clear label ("Mark returned"), and an "Undo" action in the success toast (restores `returnedAt = null`).
- Same undo pattern for other one-click changes where it's cheap.

### P7 Accessibility — [x]

- Accessible names that include the object ("Delete Hering", "Edit item 3"); loan dialog checkboxes named
  "Jurtenfenster 3" instead of "3".
- `aria-current="page"` on the active navigation link.
- Dialog titles name the object ("Report damage on Jurtenfenster 3"); damage reports list "Jurtenfenster 3" instead of
  "3".

### P8 Smaller usability points — [x]

- Resolved / open state of damage reports more visible (badge exists; add a filter or sort open reports first).
- After creating an individual material, go to its page and offer "Add item".
- Kit editor: a kit without parts can't be saved; material picker with search.
- Loan dialog: search / filter for items, show the available quantity next to bulk inputs, and summarise loans as
  "2× Jurtenfenster (3, 4)".

---

## 2. Decisions

### D1 Purchases add to the stock — [x]

Recording a purchase adds its quantity to the material's stock automatically. **(backend)**, in one transaction:

- Create: `quantity_available += quantity`.
- Update: adjust the stock by the difference of the quantities.
- Delete: subtract the quantity again; reject (409) if the stock would drop below the pieces lent out.
- Editing a material sends the stock it started from (`expectedAvailableCount`); the API rejects the update (409) if
  a purchase changed the stock in the meantime, and never accepts a stock below the pieces lent out.
- Frontend: drop the "adjust the stock separately" hint; the dialog says the stock is updated.

### D2 Damage reports and item condition — [x] (decided 2026-10-14)

Damage reports are informational only (earlier decision), so the "Damaged" count and the reports can disagree, and
every damage case needs two separate steps. Decision: a toggle in the damage dialog sets the item's status in the same
request (`itemStatus` on the report body): "Set item to In repair" when reporting (off by default, damage is often
minor), "Set item back to Active" when resolving a report of an item in repair (on by default). The report itself
stays informational.

---

## 3. New features

### Decided

- [x] **F1 Packing list in the URL** (S): selected kits, quantities and included optional parts in the query string.
  Survives reloads and can be shared as a link.
- [x] **F2 Printable packing list** (S–M): print view grouped by storage location (plus location detail), with
  checkboxes, kit names and shortages; "Copy list" includes shortages and locations too.
- [x] **F3 Add several items at once** (S): "Add items 1–12" for individual materials; "Add item" suggests the next free
  identifier. **(backend)** batch create endpoint or repeated creates in one request.
- [x] **F4 Location contents** (S): clicking a location shows everything stored there (bulk materials and items,
  grouped by location detail).
- [x] **F5 Overview page** (S–M): a separate page (not the start page, which stays the materials table) with open and
  overdue loans, items in repair or in POOR condition, and open damage reports.
- [x] **F6 German interface** (M): translate the UI to German. German only, no de/en switch; backend messages are in
  German as well.
- [x] **F7 Dark mode** (S): follow the system setting by default, with a light / dark / system toggle in the navigation
  that is remembered. The `.dark` colour tokens in `app.css` and the `mode-watcher` package are already there, but
  `<ModeWatcher />` isn't mounted and there is no toggle; check custom colours (badges, overdue / shortage highlights)
  for contrast in both themes.

### Optional, maybe later

- **Return checklist per loan** (M): tick off returned pieces, allow partial returns, report damage while checking in.
- **Packing list → loan** (M): "Lend this list to: Sommerlager" creates a loan from the calculated materials; builds
  on F1.
- **QR / short-code labels per item** (M–L): scan in the storage room to open the item for a damage report or status
  change. Adds real complexity (label printing, scanning).
- **Reservations for future dates** (L): reserve material for a date range (several camps). Significantly changes how
  availability works; only if overlapping loans really happen.

---

## 4. Suggested order

1. P1, P2 (correctness of the numbers people plan with).
2. D1 (backend + small frontend change).
3. P3–P8.
4. F1, F2, F4, F3, F5, F7.
5. F6 last, so all new texts are translated in one go.
