# Plan: Aufgaben (T-7)

Tasks linked to material: one-off and recurring tasks, rules (one recurring task per item of a material) and
routines (several tasks at once around a date, e.g. before a camp). The design was settled with the owner on
2026-11-13/14 using standalone mockups (`fe:src/routes/mockups/aufgaben`, removed when this is built); the decisions
are in TODO.md under T-7. The previous plan (T-1 backups) is done; see git history.

Status: `[ ]` open, `[x]` done. `api:` = this repo, `fe:` = `~/projects/material-frontend`.

---

## 1. Concepts

- **Task:** title, optional note, optional due date, "dringend" flag, label (Prüfung, Reparatur, Pflege, Einkauf,
  Sonstiges), optional assignee (a member), links to materials and/or individual items (several). Open until
  completed; completing records when, who (name as of then) and an optional note.
- **Checklist:** part of the note. Lines starting with `[ ] ` / `[x] ` are checkboxes; the API stores the note as
  text and knows nothing about them. Ticking a box is editing the note (editors). A recurring task's next instance
  gets the note with all boxes unticked.
- **Recurring:** `repeatMonths`. Completing creates the next task, due `repeatMonths` after completion by default
  (the completion dialog shows the date and lets you change it). The next task points to the completed one
  (`previousId`), which gives the history ("zuletzt erledigt am … von …") without matching titles.
- **Rule:** "Gasschlauch tauschen, alle 5 Jahre, für jeden Gaskocher". Only for individually tracked materials. On
  creation, one recurring task per item (active or in repair), linked to the item, due `repeatMonths` after the
  item's purchase date if known and wanted, else after today. New items of the material get theirs when created.
  The next task after completing a rule task takes title, note, assignee, label and interval from the rule, so
  editing a rule affects every task created afterwards, never existing ones.
- **Routine:** a name, what day 0 is ("Lagerbeginn"), and steps (a task template with a day offset, negative =
  before). Starting it for an occasion ("Pfingstlager 2027") and a date creates one task per step, due date + offset,
  marked with the routine and the occasion. A routine's past runs are derived from those tasks.
- **From a damage report:** a task can reference the report it came from. Neither closes the other; the completion
  dialog offers "Schadensmeldung als behoben markieren" as an explicit, unticked option.

## 2. Decisions made while planning (owner may veto in review)

1. **Deleting a rule** deletes its open tasks too (the confirmation says so); completed ones stay as history.
   Keeping them would leave orphans that recur forever on their own.
2. **Reopening a completed task** ("Rückgängig", or "Wieder öffnen" in the task dialog; the check of a done task is
   inert) also deletes the task created by that completion; the toast says so. It is the exact inverse of completing.
   If that next task is completed too, reopening answers 409 (it would fork the series).
3. **Retired items** (lost, decommissioned) get no new rule tasks; their existing open tasks stay until someone
   deletes them (no hidden side effect on retiring).
4. **Merging materials** moves task links and rules of the merged materials to the remaining one (otherwise links
   would silently disappear with the deleted materials).
5. **Viewers** see all tasks and may change, complete and reopen those assigned to them (permission
   `WORK_ON_TASKS`), but not hand them to someone else. So they can tick checklists through the normal update; owner,
   2026-11-14, instead of a separate checklist endpoint.
6. **All tasks are loaded at once** (open and done, a few hundred over years); filters are client-side.
7. "Today" on the server (default next due date, first rule due date) is the date in Europe/Berlin.

## 3. Schema (`V7__tasks.sql`)

All tables tenant-owned per the README convention (tenant_id default, composite foreign keys, RLS policy).

- `task_label` enum: `INSPECTION, REPAIR, CARE, PURCHASE, OTHER`.
- `task_rules`: id, title VARCHAR(255), material_id → materials ON DELETE CASCADE, repeat_months INT > 0,
  from_purchase BOOL, assignee_id → users ON DELETE SET NULL, label, note TEXT.
- `task_routines`: id, name VARCHAR(100) unique per tenant, anchor VARCHAR(100).
- `task_routine_steps`: id, routine_id → task_routines ON DELETE CASCADE, position, offset_days INT
  (−365…365), title, note, assignee_id → users SET NULL, label, urgent.
- `task_routine_step_links`: step_id → steps CASCADE, material_id → materials CASCADE, or item_id →
  individual_items CASCADE (exactly one).
- `tasks`: id, title, note, due_date DATE, urgent, label, assignee_id → users SET NULL, repeat_months,
  completed_at TIMESTAMPTZ, completed_by VARCHAR(255), completion_note TEXT,
  previous_id → tasks ON DELETE SET NULL (previous_id), rule_id → task_rules ON DELETE SET NULL (rule_id),
  routine_id → task_routines ON DELETE SET NULL (routine_id), occasion VARCHAR(100),
  damage_report_id → damage_reports ON DELETE SET NULL (damage_report_id); `completed_by` set iff `completed_at`.
  (`SET NULL (column)` keeps tenant_id, needs Postgres 15+; we run 18.)
- `task_links`: task_id → tasks CASCADE, material_id → materials CASCADE, or item_id → individual_items CASCADE
  (exactly one); unique per task.
- Indexes on the foreign keys used for lookups; `updated_at` triggers like the other tables.

## 4. API (`api:`)

Models (`Models.kt`): `TaskLabel`; `Task` (id, title, note, dueDate, urgent, label, assigneeId, assigneeName,
repeatMonths, materialIds, itemIds, completedAt, completedBy, completionNote, previousId, ruleId, routineId, occasion,
damageReportId); `NewTask` (the editable fields plus damageReportId on create); `TaskCompletion` (note, nextDueDate);
`TaskRule`/`NewTaskRule`; `TaskRoutine`/`NewTaskRoutine` with `RoutineStep`; `RoutineStart` (occasion, anchorDate).

Permissions: new `WORK_ON_TASKS` for all roles (like REPORT_DAMAGE: the service restricts viewers further).

| Route | Permission |
|---|---|
| `GET /tasks` (query `open`, `materialId` = linked to the material or one of its items), `GET /tasks/{id}` | READ |
| `PUT /tasks/{id}`, `POST /tasks/{id}/complete` (returns the task and the next one), `POST /tasks/{id}/reopen` | WORK_ON_TASKS (viewers: only assigned to them) |
| `POST /tasks`, `DELETE /tasks/{id}` | EDIT |
| `GET /task-rules`, `GET /task-routines` | READ |
| `POST/PUT/DELETE /task-rules…`, `POST/PUT/DELETE /task-routines…`, `POST /task-routines/{id}/start` | EDIT |

Services: `TaskService` (tasks, completion, rules, routines in one service or split into `TaskRuleService` and
`TaskRoutineService` if it grows), hooks in `ItemService.createAll` (rule tasks for new items) and
`MaterialService.merge` (move links and rules). Checks: assignee must be a member (unless unchanged, as for loans),
linked materials/items must exist, rule material must be individually tracked, completing a done task → 409.

Tests: `TaskApiTest` (CRUD, links, validation, completion with recurrence and next due date, reopen, history via
previousId, rules: creation from purchase date / today, new items, next from rule, delete; routines: write, start,
offsets; merge moves links and rules), viewer rules in `PermissionTest`, tasks/rules/routines in
`TenantIsolationTest`. `UPDATE_OPENAPI=1 ./gradlew clean test`. README: features, permission table.

## 5. Frontend (`fe:`)

- `npm run api:generate`; types in `lib/api/index.ts`; `WORK_ON_TASKS` in `lib/permissions.ts`.
- `lib/tasks.ts` (+ tests): labels, loose due dates, grouping (Überfällig / Nächste 2 Wochen / Später / Ohne Datum),
  sorting, note parsing (checklist), history chain, routine offsets text.
- Components (from the mockups, wired to the API): `TaskList`, `TaskMeta`, `TaskCheck`, `TaskDialog` (read-only
  view with tickable boxes for editors, "Bearbeiten" switches to the form), `TaskForm` (title, `NoteEditor`, dashed
  property buttons), `NoteEditor`, `NoteView`, `TaskLinkPicker` (materials and items), `CompleteDialog` (note, next
  due date, follow-ups: Kauf erfassen, item back to active, damage report resolved), `RuleDialog`, `RoutineDialog`
  with `RoutineTimeline` (drag & drop, arrow keys), `RoutineStartDialog`, `InfoHint`.
- Pages: `/[tenant]/tasks` (list, toolbar: search, assignee, Art multi-select, Erledigte; "Neue Aufgabe" and
  "Verwalten" for editors), `/[tenant]/tasks/manage` (Routinen, Regeln, Wiederkehrende Aufgaben; editors).
- Material page: Aufgaben section (open tasks of the material and its items, last completions; hidden when there
  are none); "Aufgabe erstellen" in the ⋯ menu and on open damage reports.
- Overview: "Aufgaben" card (overdue and next 2 weeks).
- Packing list: a subtle marker on lines whose material or items have an open urgent task (one set built from the
  open urgent tasks; no effect on the allocation).
- Navigation: "Aufgaben" for everyone; on phones it replaces "Lagerorte" in the bottom bar.
- Remove the mockups, the variant switcher and the `/mockups` hook exception.

## 6. Review (2026-11-14, before building)

Checked against the code and the owner's "keep it simple" rule. Changes worked in:

- `damage_reports` has no `UNIQUE (id, tenant_id)` yet, which the composite foreign key from `tasks` needs: V7 adds it.
- A rule's material can't be changed (400, like the item of a damage report): its tasks are linked to its items.
- Starting a routine whose step assignee is no longer a member creates that task unassigned instead of failing:
  the template was valid when written, and failing a whole start over it helps nobody.
- Task responses carry the assignee's display name, which viewers otherwise don't see (no VIEW_MEMBERS). Accepted:
  within a Stamm, who does a task is not sensitive, and loans already show names to editors.

Considered and left as is:

- Rule tasks follow the rule when recurring, so a manually changed assignee on one of them is reset at the next
  instance. Simple and predictable; the rule is the place to change it.
- Old purchase dates make rule tasks overdue right away. That's true information, and the rule dialog's preview
  shows it before saving.

## 7. Order

1. [x] Migration, tables, models, services, routes, tests, openapi.json, README.
2. [x] Frontend types, `lib/tasks.ts` with tests, components, pages, material page, overview, packing hint, nav.
3. [x] Remove mockups and switcher; lint, check, tests in both repos; try it in the browser against a local API.
4. [x] Work in the review findings (section 8), 2026-11-14.
5. [x] REVIEW.md progress log, TODO.md T-7 ticked.
6. [x] Browser pass of the changes, desktop and phone (DevTools device mode), 2026-11-14.
7. [ ] Commit and deploy when the owner asks.

## 8. State on 2026-11-14 and findings of the independent reviews (to work in next)

Everything above is built and uncommitted in both repos. Backend: 111 tests pass, openapi.json regenerated. Frontend:
svelte-check, eslint, prettier clean, 136 vitest tests pass. Tried in Chrome against a local API: ticking boxes,
completing with next date, follow-ups (item active, damage resolved), rule tasks, material page, overview card,
packing hint all work. Local test data: Stamm `claude-test` ("Claude Testlager") with the platform admin
`claude.test` (also EDITOR member there); its password is not kept anywhere durable, so start the API with
`ADMIN_SETUP_LINK_FOR=claude.test` for a new setup link if needed. Phone width was not tested in the browser.

Three independent agents reviewed (backend, frontend code, UI/UX). Findings, deduplicated, by priority:

**Bugs (fix):**
- [x] fe: Ticking checklist boxes quickly loses ticks (`TaskDialogs.toggle` builds each PUT from the last loaded
  note). Keep the note locally, apply ticks at once, send saves one after another.
- [x] fe: Task form (hand-rolled dialog in `TaskDialogs`) has no saving state (double "Anlegen" creates two tasks)
  and Escape / tap outside discards edits without asking. Reuse FormDialog's guard (values comparison + confirm)
  and a `saving` flag.
- [x] fe: `purchaseMaterial` is never reset, so the purchase dialog reappears after later completions; use `tick()`.
- [x] fe: Follow-ups are offered to viewers but need EDIT: only offer them with EDIT. No "Rückgängig" in the toast
  when follow-ups ran (they aren't undone). Name the failed follow-up in the error instead of the vague message.
- [x] fe: The completion dialog always sends `nextDueDate` computed from the task's own interval, so a rule's changed
  interval is ignored; send it only if the user changed the date (server default uses the rule).
- [x] fe: One tap on the check of a completed task (Erledigte list, material page) reopens it and may delete the next
  task: make the check inert for completed tasks; reopening only via "Wieder öffnen" in the dialog.
- [x] fe: Routine step "Tage" field: clearing it gives 0 and flips to "am Tag", hiding the field. Ignore empty input.
- [x] fe: Routines can end up empty (untitled steps dropped silently; card shows "Infinity Tage"). Validate step
  titles visibly; require ≥1 step (API already does); guard `span()` for no steps.
- [x] fe: Arrow keys in `RoutineTimeline` aren't clamped to ±365.
- [x] fe: NoteEditor: Shift+Enter or pasting several lines puts line breaks inside one line, which breaks checkbox
  lines on save. Treat Shift+Enter as Enter, split pasted text into lines.
- [x] api: Deleting an item cascades its task links, leaving rule tasks without links that recur forever.
  `ItemService.delete` should delete the item's open rule tasks (same rule as deleting a rule); test.
- [x] api: Reopening a task whose next task is already completed forks the series (completing again creates a second
  next). Answer 409 ("Die nächste Aufgabe ist schon erledigt …") when a completed task has `previousId = id`; test.
- [x] api: `createNext` creates rule tasks for items that are now LOST/DECOMMISSIONED (against decision 3), and
  `createNext`/`createRuleTasks` keep assignees who left the Stamm (routine start drops them). One helper for the
  member filter; skip the next rule task for retired items.

**Decided (owner, 2026-11-14):**
- [x] Viewers tick checklists: instead of a separate endpoint, viewers may change tasks assigned to them through the
  normal `PUT` (permission `COMPLETE_TASKS` renamed to `WORK_ON_TASKS`), but can't hand them to someone else. See
  decision 5.
- [x] No nav badge for overdue tasks; the overview card is enough.
- [x] The Members page subtitle stays removed.

**Smaller (do if cheap):**
- [x] api: Routine name uniqueness: drop the pre-check, map `task_routines_name_key` in `constraintMessages`
  (`ErrorHandling.kt`) and document 409, like kits/materials. Document 400 on the PUT routes. Fix `findTasks` KDoc,
  drop redundant index `idx_task_links_task_id` (only if before the migration is applied anywhere real; it's
  unreleased), untick the rule note in `createRuleTasks` too, rename shadowing `id` params, reuse `isMember` in
  LoanService. Drop `GET /tasks?open` if unused (kits page uses it — keep).
- [x] api tests: merge moving rules and routine-step links and the `keep = first()` path; routine start with a
  former member; viewer reopening others' task (403); rule with `fromPurchase = false`; damageReportId of another
  tenant.
- [x] fe duplication: shared helper for the item update body (also in `ItemTable.setStatus`); interval
  (N months/years) helpers in `tasks.ts` used by TaskForm and RuleDialog (no `$effect` write-back); shared
  member/label options and the "active or in repair" filter; `provideTaskData` should return its maps instead of
  pages rebuilding them; overview card via `grouped()`; "N weitere später" counts undated tasks too.
- [x] fe: RuleDialog preview copies server logic (first due); keep it simple or drop the toast count derived from it;
  when editing, items without an open task show a date that won't be created.
- [x] fe UX: overview card should show links (rule tasks share titles); damage-report task: short title
  ("Reparieren: Kohte K03"), description into the note; viewers: explain greyed checks ("Erledigen kann, wem die
  Aufgabe zugewiesen ist."); InfoHint: hover opens then click closes (drop hover or keep open while hovering;
  distinct labels); RuleDialog wording ("1 Jahr nach dem Kauf" instead of "jährlich nach dem Kauf", "Erste
  Fälligkeit"), shadcn RadioGroup instead of native radios; plurals ("1 Aufgaben", "1 von 5 Punkten sind");
  "seit 26 Monaten" → years; "Tag 0 ist der" breaks for feminine words; search should cover `occasion`.
- [x] fe a11y/touch: `pointer-coarse:h-11` on property buttons, corner checkbox button, packing-hint icon; `touch-none`
  only on the timeline grip; assignee avatar initials + sr-only name; labels for SelectFields (assignee filter,
  routine selects, repeat unit), note editor, phone "Verwalten" button; `aria-pressed` on "Dringend"; property buttons
  read "Fällig: in 3 Wochen"; checkbox labels in NoteEditor; check label "„X“ als erledigt markieren"; visible focus on
  the title input; scroll the selected timeline step's fields into view.
- [x] fe tests for `tasks.ts`: boundaries of `looseDue`/`groupOf` (13/14, 59/60 days, "im März" same year),
  `loosePast` near midnight, `isOverdue` for completed, `historyOf` skipping open ones, note parsing edge cases
  (`[x]` without space, `[X]`, `- [ ]`), `toggleLine` on a non-checkbox line, unknown ids in `linkNames`/
  `materialIdsOf`, `mayComplete`, `repeatText(1/18/24)`, `offsetText(0, '')`.
- Later / maybe: deep links (`?q=` on /tasks for routine runs and the packing hint), shadcn Drawer for pickers on
  phones (test on a real device), limit for the Erledigte list.

### Worked in (2026-11-14)

All items above are done; two agents did the frontend bugs and cleanups, one added `tasks.ts` edge-case tests. A
second independent review of the result found, besides frontend races in `TaskDialogs` (ticks per task, save bound to
the form it started from, hidden close button while saving, stale checklist count, double taps), that a rule's "one
open task per item" broke for items found again and after a merge. `createMissingRuleTasks` now skips (rule, item)
pairs with an open task and runs on item create/update, damage-report status changes and merge. All worked in.
Backend 114 tests, frontend 215 tests, check and lint clean; openapi.json and `schema.d.ts` regenerated. The local dev
database got V7's changed checksum and lost the dropped index by hand. Not tried in the browser after these changes.
