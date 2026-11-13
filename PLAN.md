# Plan: off-site backups (T-1)

**Done 2026-11-13.** The owner set up R2, healthchecks.io and the VPS and restored successfully from R2. Reviewed by
an independent agent before building; its findings are worked in (section 5). The previous plan (FE-7, FE-8) is done; see git history and REVIEW.md.

Status: `[ ]` open, `[x]` done, `[?]` waiting for a decision. Who: **C** = Claude (this repo and
`~/projects/vps-deployments`), **O** = owner (Cloudflare account, VPS, Bitwarden).

---

## 0. Situation

- Only the material database holds data on the VPS. Everything else is in git (`vps-deployments`) or can be made
  again (certificates: certbot; the letter app and personal website have no volumes).
- `material/backup-db.sh` writes a `pg_dump -Fc` to `material/backups/` on the VPS and keeps 30 days. The cron job is
  documented in `material/README.md` but not enabled. Nothing leaves the VPS.
- The database is a few MB and contains personal data (usernames, borrower names).

## 1. Decisions (owner, 2026-11-13)

- **Off-site target: restic → Cloudflare R2** (free up to 10 GB; the owner's Cloudflare account, a card is already on
  file). Considered and dropped: B2 (the same thing with another account), Hetzner Storage Box (paid), a pull to a home
  machine, Google Drive. Hetzner Cloud Backups (~20 % of the server price) would be a good extra against broken
  updates; skipped for now to stay free.
- **Secrets go into the owner's Bitwarden**: restic password, R2 keys, endpoint, healthchecks ping URL. Without the
  restic password the backups can't be decrypted, so this is mandatory, not optional.
- **Bus factor accepted for now:** the owner holds all secrets (Bitwarden) and the Cloudflare account. Considered: a
  free two-person Bitwarden organisation, a sealed printout with the Vorstand. The README says who holds them.
- **The `vps-deployments` repo is public**, so nothing secret goes into it: keys, password and the ping URL (anyone
  with it could send fake "OK" pings) live only in `backup.env` and Bitwarden.
- **Keep it simple:** no object lock or append-only setup against a compromised VPS (it could delete the backups; we
  accept that), no extra tooling beyond restic.

## 2. Design

- **One script, one cron job.** `backup-db.sh` keeps writing the local dump (useful for quick restores and the manual
  backup before updates), then pushes the same dump to R2:

  ```sh
  restic backup --host material --stdin-filename scout_material.dump --stdin < "$file"
  restic forget --host material --keep-daily 7 --keep-weekly 5 --keep-monthly 12 --prune
  restic check --read-data
  curl -fsS -m 10 --retry 5 -o /dev/null "$HC_PING_URL"
  ```

  - `--stdin-filename` gives every snapshot the same file name, so a restore is `restic dump latest
    scout_material.dump`, without knowing dates or VPS paths.
  - `--host material` fixes the name restic groups snapshots by, so a rebuilt server continues the same series
    instead of starting a second one that `forget` treats separately.
  - Retention: one per day for a week, one per week for 5 weeks, one per month for a year. At a few MB each this
    stays far below 10 GB.
  - `check --read-data` downloads and verifies everything stored (a few MB, free egress), so silent corruption in
    R2 also stops the ping.
  - Local copies: 30 days, as now, but readable only by the deploy user (`umask 077`; they hold personal data
    unencrypted).
  - `set -euo pipefail`: any failing step stops the script before the ping, so healthchecks.io alerts. The local dump
    is kept; the error lands in `backup.log`. A missing `backup.env` is an error, not a reason to skip the push.
  - Not handled (rare): a manual run at the same moment as the cron run. restic's lock makes one of them fail, which
    only costs one alert.
- **Configuration:** `material/backup.env` on the VPS (git-ignored, `chmod 600`), read by the script:

  ```sh
  RESTIC_REPOSITORY=s3:https://<account-id>.eu.r2.cloudflarestorage.com/material-backups
  RESTIC_PASSWORD=…          # openssl rand -base64 32
  AWS_ACCESS_KEY_ID=…        # R2 API token, "Object Read & Write", this bucket only
  AWS_SECRET_ACCESS_KEY=…
  AWS_DEFAULT_REGION=auto
  HC_PING_URL=https://hc-ping.com/…
  ```

  Loaded with `set -a; . ./backup.env; set +a` (plain sourcing wouldn't export the variables to restic). The README
  gives the same line for interactive commands (`restic init`, `restic snapshots`, restores).

  A `backup.env.example` without values goes into git. The bucket uses Cloudflare's **EU jurisdiction** (data stays in
  the EU; free). Such a bucket is only reachable through the `.eu.` endpoint, and the bucket-scoped token must be
  created for it; if `restic init` says Access Denied, check these two first. restic needs delete rights for
  `forget --prune`, so the token can't be read-only.
- **restic on the VPS from the distribution's package** (`apt install restic`): no container to manage, and a fresh
  server needs the same one command.
- **Noticing failures: healthchecks.io** (owner, 2026-11-13): a check with cron schedule `15 3 * * *` in the VPS's
  timezone and about 1 hour grace; pinged only when everything succeeded (see above).

## 3. Steps

1. `[x]` **C** `backup-db.sh` as above.
2. `[x]` **C** `material/.gitignore`: `backup.env`; add `backup.env.example`.
3. `[x]` **C** `material/README.md`, section "Backups" rewritten:
   - one-time setup: R2 bucket and token, `backup.env`, `restic init`, crontab line, entries in Bitwarden (what
     exactly to store);
   - checking: `restic snapshots` shows the latest backups;
   - restore into the running database (as now, plus `restic dump` to fetch a dump from R2);
   - **restore onto a fresh server**: clone repo, `.env` with a new `POSTGRES_PASSWORD`, `backup.env` from Bitwarden,
     start only `material-db`, `CREATE ROLE material_app NOLOGIN` (the role belongs to the server, so the dump doesn't
     contain it and its GRANTs fail without it), then

     ```sh
     restic dump latest scout_material.dump \
       | docker compose exec -T material-db pg_restore -U scout -d scout_material --exit-on-error --single-transaction
     ```

     and start the rest; the API's Flyway sees the schema history from the dump and only applies newer migrations.
     Without `--exit-on-error --single-transaction`, `pg_restore` carries on past errors and leaves a partial database
     the API would happily start on; with them a failure leaves the database empty to retry. If `pg_restore` can't
     read the piped archive (non-seekable input), write it to a file and `docker cp` it in; the test restore decides;
   - one sentence on data protection: deleted personal data stays in the backups for up to 12 months (retention);
   - "Future improvements": remove the off-site item, keep Hetzner Cloud Backups as an option.
   Tested locally 2026-11-13 (restic 0.18 in Docker, a local repository, the 2026-10-22 production dump): the script
   backs up and exits 0; a wrong password and a missing `backup.env` stop it before the ping; the README's "Test a
   restore" commands, run as written, restore through the pipe with identical row counts. `forget` keeps the oldest
   snapshot while the daily/weekly/monthly slots aren't full yet (restic ≥ 0.17), so early on there are more
   snapshots than the policy suggests. Not testable here: R2 itself, cron, the real ping.
4. `[x]` **O** healthchecks.io: account and check (see 2), ping URL into `backup.env` and Bitwarden. Chosen over
   "look at `restic snapshots` when deploying" because a backup that silently stopped is the usual way backups fail.
5. `[x]` **O** Cloudflare: create bucket `material-backups` (EU jurisdiction) and an R2 API token limited to it.
6. `[x]` **O** VPS: pull the repo, `apt install restic`, fill `backup.env`, `restic init`, store the values in
   Bitwarden, run `./backup-db.sh` once by hand, enable the cron job.
7. `[x]` **O** (Claude walks through it) **Test restore** into a throwaway Postgres container on the VPS, with exactly
   the fresh-server commands from the README, and compare row counts with the live database. Also let the
   healthchecks check go red once (e.g. a wrong key in a copy of `backup.env`) to see the alert arrive. Fix the README
   where it was wrong.
8. `[x]` **C** Tick T-1 in TODO.md, entry in the REVIEW.md progress log, update `material/README.md`'s "Not set up yet".

## 4. Not in scope

- Backups of anything but the material database (see 0).
- Photos (T-15): they'd need to go into the same restic repository once they exist; `latest` must then be narrowed
  with `--path /scout_material.dump`.
- A second copy elsewhere, Hetzner Cloud Backups, object lock.

## 5. Review (2026-11-13)

An independent review agent checked the plan against the scripts, compose file, migrations and restic/R2 docs.
Worked in above: no skipping the push without `backup.env` (would ping success with no off-site copy), ping only
after everything succeeded and its URL out of the public repo, `set -a` to export the variables, `pg_restore
--exit-on-error --single-transaction`, the EU-jurisdiction endpoint/token caveat, `umask 077`, `restic check
--read-data`, the second-person question, the data-protection sentence.
Found correct: the restic flags (`--stdin-filename`, `--host`, `dump latest scout_material.dump` without a leading
slash; `forget` grouping), restic versions in Debian/Ubuntu, the R2 endpoint and `AWS_DEFAULT_REGION=auto`, running
from cron, and the restore: the dump contains the RLS policies, grants and Flyway history; the migrations have no
extensions or database-level settings, so `CREATE ROLE material_app NOLOGIN` is the only thing to add (scout is a
superuser, so `GRANT material_app TO scout` is optional).
Not taken: `flock` against parallel runs (rare; costs one alert at most).
