# TODO

Backlog for the scout material management: this repo (`material-api`, Ktor backend), `~/projects/material-frontend`
(SvelteKit) and the deployment in `~/projects/vps-deployments`. Written on 2026-11-06 after an exploration (one pass
on polishing what exists, one on new features) and a Q&A with the owner (the user, who is also the developer).

## How to use this file

- **Read first:** `CLAUDE.md` (repos, live instance, conventions), `README.md` (API, schema, tenant/RLS rules),
  `REVIEW.md` (decisions made so far, open domain questions, progress log). Respect earlier decisions there (e.g. no
  stock ledger, kits can't contain kits, no nested locations, loans are a simple book) unless the owner reopens them.
- **Workflow used so far:** for anything bigger than a small fix, write a plan into `PLAN.md` (it currently holds a
  finished plan and can be replaced), get the owner's OK, build, then add an entry to the progress log in `REVIEW.md`
  and tick the item here. UI designs with several plausible options were compared live with a temporary dev-only
  switcher in the running app (the `ui-variants` skill; see the FE-9 entry in REVIEW.md); the owner liked that.
- **Keep it simple (owner, 2026-11-10):** don't add UX or code complexity for rare cases or small gains: no hidden
  side effects on other records, no frontend copies of API rules just to predict them, no machinery against parallel
  editing (about 1 user per Stamm). Prefer explicit, predictable behaviour and leave rare cases unhandled. See the
  2026-11-11 simplification batch in REVIEW.md.
- **Status:** `[ ]` open and clear enough to build, `[~]` needs exploration or decisions with the owner first, `[x]` done.
- **Paths:** `api:` = this repo, `fe:` = `~/projects/material-frontend`, `deploy:` = `~/projects/vps-deployments`.
  Line numbers are from 2026-11-06 and may have drifted; search for the quoted code if they don't match.
- **Glossary:** *Stamm* = local scout group (one tenant). *Materialwart* = the volunteer responsible for the material.
  *Vorstand* = the Stamm's board. *Stufen* = age groups. *Zeltlager / Lager* = camp. *Jurte, Kohte* = traditional scout
  tents made of canvas panels (*Planen*). *Heringe* = tent pegs. *Inventur* = stock count. UI text is German, code and
  docs English.

## Context that shaped this list

The tool isn't in real use yet (demo data only). It will mostly be used by the Materialwart and Vorstand, on desktop
and phone about equally (damage reports, quick lookups and packing often happen without a PC). Other members would at
most use the loan book. The real pain is **knowing what the Stamm owns**, and **care and replacement**. Taking
material inside the Stamm is informal (no loans), scheduling conflicts don't happen (the Stamm mostly uses material as
one group), and real loans go to other Stämme. Individual items (tents, Planen) carry painted/written labels; most
material is on shelves, crates are mostly for transport. The owner prefers free hosting options and judges designs by
maintainability, UX and security rather than by how much code changes.

## Suggested order

1. Done 2026-11-09: T-2, T-4, T-5, T-6, T-8, T-9, T-10, T-11, T-12 (see REVIEW.md). Left: T-13 (owner deploys), then
   the follow-ups T-18 … T-21 (done 2026-11-10 except T-19, the owner's check on an iPhone).
2. T-1 (backups) before real data is entered; needs the owner for the Cloudflare account and storing secrets.
3. T-3 (Inventur) and T-7 (Aufgaben): explore with the owner first (open questions listed), compare designs live,
   then plan. T-5 and T-6 help T-3 and are worth doing before it.

After an API change: `UPDATE_OPENAPI=1 ./gradlew clean test` here, then `npm run api:generate` in the frontend (see
CLAUDE.md). Deploying is manual: images are built and pushed, the owner rolls them out on the VPS.

---

## Before real data goes in

- [~] **T-1 Backup procedure.** Needs planning and setup, not just turning a cron job on. Today
  `deploy:material/backup-db.sh` writes a daily `pg_dump` to the VPS's own disk (keeps 30 days), but
  `deploy:material/README.md` ("Backups") says "Not set up yet (test phase)", and nothing copies backups off the VPS.
  To decide, set up and document in that README:
  - **Where backups go.** Same-VPS copies don't survive losing the VPS. The data includes personal data (usernames,
    borrower names), so off-site copies must be encrypted. The database is a few MB; the owner prefers free.
    Options considered (prices as of 2026-11, check before signing up):

    | Option | Cost | For | Against |
    |---|---|---|---|
    | restic → Backblaze B2 (S3-like object storage) | first 10 GB free | restic supports it natively; encrypted, deduplicated, years of dailies fit in 10 GB; key limited to one bucket | another account to own |
    | **restic → Cloudflare R2** | 10 GB free | same as B2; no extra provider since the owner already uses Cloudflare | reportedly needs a payment method on file |
    | Pull to a machine at home (rsync/scp) | free | the VPS holds no credentials to the backups, so a compromised VPS can't delete them | only runs while that machine is on; it's one person's machine, not the Stamm's |
    | Google Drive etc. via rclone | free (15 GB) | many people have one | the token on the VPS can usually reach the whole drive; needs a dedicated account; clunky |
    | Hetzner Storage Box | ~4 €/month | simplest if the VPS is at Hetzner | paying for 1 TB to store MBs |

    **Chosen (2026-11-06): restic → Cloudflare R2**, since the owner already has a Cloudflare account (domain
    hosting). Run from the existing cron job right after `pg_dump`, with an R2 API token limited to the backup bucket.
    Check whether R2 asks for a payment method; usage stays far below the free 10 GB. Optionally a home pull as a
    second copy.
  - **Frequency and retention** off-site: daily; `restic forget --keep-daily 7 --keep-weekly 5 --keep-monthly 12
    --prune`.
  - **Secrets and bus factor:** the restic password and the R2 token must also be kept outside the VPS (the Stamm's
    password manager or with a second person); without the password, the backups can't be decrypted after losing the
    VPS. A successor Materialwart or admin should be able to restore from the README alone.
  - **Noticing failures:** a ping at the end of the job (e.g. healthchecks.io, free tier) that alerts when backups stop.
  - **Restore onto a fresh server:** the README only covers restoring into the running database. The Postgres role
    `material_app` belongs to the server, not the database, so `pg_dump` doesn't contain it; restored into a new volume,
    the dump's GRANTs fail and the API fails at `SET LOCAL ROLE` (`api:src/main/kotlin/database/Database.kt:68`).
    The role is created by migration `V2__accounts_and_tenants.sql`, which Flyway won't run again on a restored
    database. The steps need `CREATE ROLE material_app NOLOGIN; GRANT material_app TO scout;` before `pg_restore`
    (REVIEW.md has this for local restores, search for `CREATE ROLE material_app`).
  - **One test restore** onto a fresh database, written down as the procedure.
  - Later, if photos come (T-15), they need to be in the backup too.
- [x] **T-2 iOS bug in the material list.** `fe:src/routes/[tenant]/+page.svelte:57-59` (`categoryCounts`) calls
  `.entries().map(...)` on a Map. Iterator helpers only exist from Safari 18.4, so on iOS 17.4–18.3 the page's script
  fails and search, menus and dialogs likely don't work. Fix: `[...map].map(...)`. The same kind of bug was fixed once
  before (REVIEW.md, "no iterator helpers"); add a lint rule for a minimum browser version (e.g.
  `eslint-plugin-compat` or `eslint-plugin-es-x`) so it doesn't come back. `Map.groupBy` is used widely and already
  requires iOS 17.4; that's accepted.

## Knowing what we own

- [~] **T-3 Inventur mode.** A guided stock count that is also the way to enter the real inventory the first time.
  Owner (2026-11-06): entering on a desktop/laptop first is fine (a laptop can come to the storage room); support the
  phone only if it's actually good there; spreading the work over several sessions is nice to have.

  **Concept (draft, not agreed in detail):**
  - **An Inventur is a stored session**, not a browser draft: it spans several visits and both laptop and phone, so
    counts are saved on the server as they are entered. "Inventur starten" (name, e.g. "Inventur Herbst 2026"),
    scope all or selected storage locations. One open Inventur per Stamm at a time.
  - **Per storage location** a checklist of what should be there (bulk stock and items with that location, sorted by
    `location_detail`, the free-text shelf/compartment field):
    - bulk: "Heringe (Regal 3 · Fach 2): erwartet 120, gezählt [__]". "Expected" is stock minus what's lent out.
    - items: one row per identifier, "da" / "fehlt", optionally condition and location detail on the spot.
    - "Hier noch gefunden …": an existing material that's stored elsewhere, or a **new material** with a short form
      (name, category with suggestions from T-5, tracking type, count or identifiers). This makes the first entry the
      same flow as a recount, only with empty "expected" columns.
  - **Not counted ≠ zero:** rows nobody touched stay "nicht gezählt" and change nothing. Progress per location
    ("Regal 3: 14 von 20 gezählt").
  - **Desktop:** a table per location, keyboard-friendly (type a number, Enter goes to the next row; quick rows for new
    materials). **Phone:** one location at a time, large number fields with −/+, sticky "Weiter". Same data, two
    layouts.
  - **Review and apply:** a list of differences (bulk: expected vs. counted; items: missing, found elsewhere,
    condition changed; new materials), each with a checkbox, applied in one transaction. Missing items are offered
    as LOST, not set automatically. The finished Inventur stays readable as a record ("Heringe: 120 → 104,
    Inventur Herbst 2026"), which answers "where did the Heringe go" without a general stock ledger (decided against
    in REVIEW.md, DB-3).
  - **Data (rough):** `inventories` (name, started/finished, scope), `inventory_counts` (variant, location, counted,
    by, at), `inventory_item_checks` (item, seen, location, condition). Tenant-owned tables following the RLS
    convention in README.md.
  - **Open, to explore with the owner:** what happens to normal edits while an Inventur is open (likely allowed; the
    review compares against the stock at apply time); whether several people count different shelves at once; how
    the screens look (compare live with the variant switcher before building).
- [x] **T-4 Search by item label.** The material search (`fe:src/routes/[tenant]/+page.svelte:119-126`) looks at
  names, category, description, location and variant names, but not item identifiers. Typing "K07" should find the
  material and show the item (expand the row). The loan dialog's search already includes identifiers
  (`fe:src/lib/components/LoanDialog.svelte:95-97`).
- [x] **T-5 Category suggestions (closes DB-7c in REVIEW.md).** The category field
  (`fe:src/lib/components/MaterialDialog.svelte`) is a plain input, so typos ("Zelt", "Zelte", "zelte ") become
  separate sections in the category view of the material list. Suggest existing categories while typing
  (datalist/combobox); the API trims and adopts the spelling of an existing category that matches case-insensitively.
  Also trim names in the API (`requireText`, `api:src/main/kotlin/api/Models.kt`, doesn't trim), so "Seil " can't get
  past the name uniqueness check. No lookup table.
  2026-11-10: the adoption of existing spellings, and the respelling of a whole category it needed, were dropped
  (owner: odd, complex, no real benefit). The API only trims; the suggestions remain. A real "Kategorie umbenennen"
  could come later if needed.
- [x] **T-6 Archiving materials, unobtrusively.** A material that was ever lent, bought or used in a kit can't be
  deleted (foreign keys with RESTRICT, messages in `api:src/main/kotlin/configure/ErrorHandling.kt`) and has no
  retired state, so sold or worn-out gear stays in every list. Add `archived_at` (new Flyway migration); archived
  materials disappear from the material list, packing list, kit editor and loan dialog, with a quiet way to see them
  (e.g. an "Archivierte" filter entry), and "Archivieren" offered where deleting is refused. Same spirit for items:
  hide DECOMMISSIONED/LOST ones behind a toggle in `fe:src/lib/components/ItemTable.svelte`. Keep it unobtrusive
  (owner's condition).

## Care and replacement

- [~] **T-7 Aufgaben: one-off and recurring tasks linked to material.** A small built-in task list rather than a
  special "inspection" feature. Examples: "Gasschlauch Kocher 2 tauschen" (due 03/2027; the gas hose expires and is
  replaced, the cooker stays the same item), "Erste-Hilfe-Kasten prüfen" every 12 months, "Jurte 1 nach Lager
  trocknen" (one-off, urgent; wet tents get mouldy), "Kohten imprägnieren" every few years.
  Owner (2026-11-06): tasks show in three places: due/overdue on the overview page (`fe:src/routes/[tenant]/overview`),
  an own "Aufgaben" page, and on the material/item. Damage reports stay separate, but a task can be created from one.
  The Aufgaben page may grow into the one place for managing the material, so good task-list features are welcome.

  **Concept (draft, not agreed in detail), core:**
  - **A task** has a title, optional note, optional due date, optional links (material, variant, item, kit or storage
    location; several allowed, e.g. "Kocher 1–3"), optional assignee (a member, or free text for people without an
    account), and a label (Prüfung, Reparatur, Pflege, Einkauf, Sonstiges).
  - **Completing** records who and when, plus an optional note ("2 Pflaster nachgekauft"). Completed tasks stay as
    history, so the material page can show "Erste-Hilfe-Kasten: zuletzt geprüft 03/2026 (Anna)".
  - **Recurring:** "alle N Monate/Jahre ab Erledigung" (default; matches how volunteers work: a late check moves the
    next one) or "jedes Jahr im März" (fixed, e.g. before the summer camp). Completing creates the next one.
  - **From a damage report:** "Aufgabe erstellen" pre-fills "Reparieren: Jurte 2 – Riss am Dach", linked to the item
    and the report. Whether resolving one completes the other is still to decide.
  - **Aufgaben page:** groups Überfällig / Diese Woche / Später / Ohne Datum; filters for "Meine", label, category;
    an "Erledigt" history. The overview shows only overdue and due soon.

  **Ideas beyond the core (pick later with the owner):**
  - **Checklists inside a task**, kept for recurring ones: "Erste-Hilfe-Kasten prüfen" with Pflaster, Rettungsdecke,
    Ablaufdaten … ticked each time.
  - **Routines started by hand:** "Nach dem Lager" creates "Zelte trocknen", "Heringe zählen", "Kochkisten spülen" in
    one go; "Vor dem Sommerlager" likewise. This covers checking material in after a camp without using loans.
  - **One rule for many items:** "Gasschlauch tauschen alle 5 Jahre" for every Gaskocher, one task per item with its
    own due date (from purchase date or last replacement).
  - **Actions on completion:** an "Einkauf" task offers "Kauf erfassen" (purchase dialog pre-filled); a "Reparatur"
    task offers setting the item back to ACTIVE.
  - **Before a camp:** the packing list warns if a packed item has an open task ("Kohte 3: Reparatur offen").
  - Maybe later: a personal iCal feed of "Meine Aufgaben" (needs a secret URL; declined for now, see below).

  **Open, to explore with the owner:** permissions (likely: viewers see tasks and complete ones assigned to them;
  editors create and edit); whether "Einkauf" tasks form a simple shopping list view; the page layout (compare live
  before building).
- [x] **T-8 Damaged bulk stock on the overview, separately.** The overview (`fe:src/routes/[tenant]/overview/
  +page.svelte:25-33`) lists only individual items and says "Alle Gegenstände sind in Ordnung." even when bulk stock
  is marked damaged (`quantity_damaged`). Show bulk damage too, but separate and low-key: a few bad Heringe matter much
  less than a broken tent.
- [x] **T-9 Printable inventory report.** For the Vorstand, the yearly cash audit or insurance: per category, counts,
  purchase year and price where known, totals, date. Print CSS like the packing list's print view (maybe also CSV). No
  depreciation.

## Polish

- [x] **T-10 Confirmation for "Rückgabe eintragen" for viewers.** Viewers (role VIEWER) can mark their own loan as
  returned but can't undo it (`api:src/main/kotlin/services/LoanService.kt:46-48`; "Rückgängig" is editor-only), and
  the button has no confirmation (`fe:src/routes/[tenant]/loans/+page.svelte:115-121`). Add a confirmation for users
  who can't undo.
- [x] **T-11 Form dialogs on phones.** In `fe:src/lib/components/FormDialog.svelte`: a tap outside or Escape closes
  the dialog and loses the input (ignore outside clicks, or ask only when there are unsaved changes); `max-h-[90vh]`
  ignores the phone keyboard and browser bars (use `dvh`); the save button scrolls away in long dialogs (sticky footer;
  drop the nested scroll area at `fe:src/lib/components/LoanDialog.svelte:328`).
- [x] **T-12 No raw UUIDs in messages.** `notFound` (`api:src/main/kotlin/services/Exceptions.kt:31`) produces e.g.
  "Material 3f2a…-… wurde nicht gefunden.", and so do "… <uuid> gibt es nicht." in
  `api:src/main/kotlin/services/References.kt:21-54` and `LoanService.kt:95`. The realistic cause is a stale browser
  tab: name what's missing in words and suggest reloading ("…gibt es nicht mehr. Lade die Seite neu."), as the merge
  already does. Related: `fe:src/routes/+error.svelte` is a bare "Fehler 502" with no reload button, which is what
  users see while the API restarts.
- [ ] **T-13 nginx keeps the frontend's address from startup.** `deploy:proxy/nginx.conf:179,188` resolve
  `material-frontend` once when nginx starts; a recreated container can get a new IP, giving 502s until nginx reloads.
  Fix: `resolver 127.0.0.11 valid=10s;` (Docker's DNS) and `set $material http://material-frontend:3000;
  proxy_pass $material;` (same for the other sites in that file). The owner deploys it.

- [x] **T-18 Material list overflows at 375 px for editors** (existed before 2026-11-09). The table is ~410 px wide (chevron,
  name, Verfügbar, two 44 px action buttons) against ~341 px available, so the page scrolls sideways and form dialogs
  opened from the list are shifted right with their footer cut off. Options: `overflow-wrap:anywhere` on the name cell
  (removed the overflow in a test), or "Bearbeiten" into the ⋯ menu on phones. Done 2026-11-10, see REVIEW.md.
- [ ] **T-19 Check the 2026-11-09 and 2026-11-10 changes on an iPhone** (owner, about 5 minutes after the next
  deploy). Playwright's WebKit doesn't run on this machine, and an emulated WebKit wouldn't show the risky part
  anyway: how iOS Safari's keyboard and toolbars resize dialogs. Checklist: material list scrolls without errors (T-2)
  and doesn't scroll sideways (T-18); ⋯ menu has "Bearbeiten"; in "Material anlegen", tap into a field low in the
  dialog: the field stays visible and the footer buttons are reachable; loan dialog: material search stays on top
  while scrolling; Inventarliste: "CSV" downloads a file, long names hyphenate.
- [x] **T-20 Inventory report columns line up across categories.** Each category's table sizes its own columns; fixed
  column widths would align them in print. Done 2026-11-10. Not done: per-year purchase totals for the cash audit
  (only if the Vorstand asks for them).
- [x] **T-21 Small wording issues.** The toast after creating a material uses the untrimmed input ("E2E Kochtopf 
  angelegt" with a double space; use the API's returned name). The overview's "Sonst ist nichts ausgeliehen." also
  shows when nothing is overdue. Both fixed 2026-11-10. The error page retrying by itself on 502/503: decided against
  by the owner.

## Later

- [ ] **T-14 Excel/CSV import.** A neighbouring Stamm keeps its inventory in an Excel file; the owner wants to get a
  copy first, then design the import around a real file.
- [ ] **T-15 Photos.** Mainly for kits (what a packed Jurte looks like), then materials and damage reports. Nice, not
  important. Needs storage, size limits, serving through the tenant-checked API, and backups (T-1).
- [ ] **T-16 CI and versioned images.** Neither repo has CI. Not a priority while deploying is manual anyway. Would run
  the tests on push and build images tagged by commit instead of `latest` from a local working copy.

## Far future

- [ ] **T-17 QR labels.** For items, boxes or shelves, opening the item or a damage report. Not now: the Stamm isn't
  organised enough yet, the initial effort isn't worth it, most users wouldn't enjoy it, and labels drift from
  reality. A wrong text label is harmless, but a QR code that confidently opens the wrong thing is worse.

## Decided against (2026-11-06)

- **Target stock and shopping list:** only one or two materials have a real target amount.
- **Loading indicator:** not needed while pages are fast.
- **Saved/dated packing lists with conflict hints, and turning a packing list into a loan:** scheduling conflicts
  don't happen, and taking material inside the Stamm is informal. Checking material in after a camp is covered by
  T-7's routines instead.
- **Public lending catalogue for other Stämme, loans across tenants, offline mode, notifications/iCal:** no need now.
