# Plan: usability round

Based on the UI/UX review of 2026-10-13 (see REVIEW.md for the earlier reviews). Covers both repositories; most work
is in material-frontend, backend changes are marked **(backend)**.

Status: `[ ]` open, `[x]` done, `[?]` waiting for a decision.

---

## 1. Problem fixes

### P1 One meaning of "available" — [ ]

The materials list respects the "count lent out pieces as unavailable" switch, the material page always shows
`availableCount` including lent pieces (e.g. Hering: 70 in the list, 120 on the detail page).

- Use `availableNow()` (or an explicit breakdown) on every page that shows availability: material page, kit cards,
  packing list, loan dialog.
- On the material page show the full breakdown, e.g. "120 in stock · 70 available · 50 lent out · 0 damaged", so the
  numbers explain each other.

### P2 Readable kit alternatives and shortages — [ ]

- Kit cards: show alternatives as "2× Jurtenhalbdach **or** 6× Kohtenblatt", label the preferred one in words
  ("preferred") instead of an arrow that looks like an expand button.
- Packing list: report shortages per part, e.g. "Dach: 2 units short (1 Jurtenhalbdach or 2 Kohtenblatt)", instead of
  only "1 Jurtenhalbdach missing". Needs `allocate()` to return missing units per group in addition to the material
  totals (with tests).

### P3 Phone use — [ ]

- Loans: show lent / due date and the overdue badge below the borrower on small screens.
- Kits: packing list reachable without scrolling past all kits (sticky "Packing list (n)" bar that jumps to / opens
  the list, or list first on small screens).
- Navigation: labels visible (or a labelled bottom bar) on small screens, not only icons.
- Item table: no sideways scrolling that hides the actions; stacked layout or an actions menu on small screens.
- Touch targets of at least 44 px for row actions on touch devices; don't place Edit directly next to Delete.

### P4 Deleting things that are still in use — [ ]

Deleting an item with damage reports or loans asks for confirmation and then fails with 409.

- **(backend)** Expose whether an item has history (e.g. `hasHistory` on `IndividualItem`), or a similar flag where
  deletes are restricted.
- Frontend: disable Delete for such items with the hint "Has history – set the status to Decommissioned instead",
  ideally with a one-click "Decommission".

### P5 Error messages, dates and prices — [ ]

- **(backend)** Validation messages in plain words instead of field names ("The due date can't be before the lent
  date" instead of "dueAt must not be before lentAt"); review all `require(...)` messages in `api/Models.kt`.
- Frontend wording consistent with the UI ("Choose a material for every alternative", not "option"); duplicate-name
  conflicts should say what clashed.
- Show form errors next to the field where it's cheap (client-side checks), toasts for the rest.
- `<html lang="de">` (or matching the chosen UI language) so date pickers show dd.mm.yyyy.
- Prices formatted as currency ("0,35 €").

### P6 "Returned today" — [ ]

Single click on an ambiguous icon, no confirmation, no undo.

- Clear label ("Mark returned"), and an "Undo" action in the success toast (restores `returnedAt = null`).
- Same undo pattern for other one-click changes where it's cheap.

### P7 Accessibility — [ ]

- Accessible names that include the object ("Delete Hering", "Edit item 3"); loan dialog checkboxes named
  "Jurtenfenster 3" instead of "3".
- `aria-current="page"` on the active navigation link.
- Dialog titles name the object ("Report damage on Jurtenfenster 3"); damage reports list "Jurtenfenster 3" instead of
  "3".

### P8 Smaller usability points — [ ]

- Resolved / open state of damage reports more visible (badge exists; add a filter or sort open reports first).
- After creating an individual material, go to its page and offer "Add item".
- Kit editor: a kit without parts can't be saved; material picker with search.
- Loan dialog: search / filter for items, show the available quantity next to bulk inputs, and summarise loans as
  "2× Jurtenfenster (3, 4)".

---

## 2. Decisions

### D1 Purchases add to the stock — [ ] (decided)

Recording a purchase adds its quantity to the material's stock automatically. **(backend)**, in one transaction:

- Create: `quantity_available += quantity`.
- Update: adjust the stock by the difference of the quantities.
- Delete: subtract the quantity again; reject (409) if the stock would become negative.
- Frontend: drop the "adjust the stock separately" hint; the dialog says the stock is updated.

### D2 Damage reports and item condition — [?] (open question)

Damage reports are informational only (earlier decision), so the "Damaged" count and the reports can disagree, and
every damage case needs two separate steps. Proposal: a checkbox "Set item to In repair" (and/or a condition select)
in the damage dialog that also updates the item; the report itself stays informational. Waiting for a decision.

---

## 3. New features

### Decided

- [ ] **F1 Packing list in the URL** (S): selected kits, quantities and included optional parts in the query string.
  Survives reloads and can be shared as a link.
- [ ] **F2 Printable packing list** (S–M): print view grouped by storage location (plus location detail), with
  checkboxes, kit names and shortages; "Copy list" includes shortages and locations too.
- [ ] **F3 Add several items at once** (S): "Add items 1–12" for individual materials; "Add item" suggests the next free
  identifier. **(backend)** batch create endpoint or repeated creates in one request.
- [ ] **F4 Location contents** (S): clicking a location shows everything stored there (bulk materials and items,
  grouped by location detail).
- [ ] **F5 Overview page** (S–M): a separate page (not the start page, which stays the materials table) with open and
  overdue loans, items in repair or in POOR condition, and open damage reports.
- [ ] **F6 German interface** (M): translate the UI to German. Decide whether a de/en switch is worth it or German only
  (simpler); backend messages need the same language.

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
4. F1, F2, F4, F3, F5.
5. F6 last, so all new texts are translated in one go.
