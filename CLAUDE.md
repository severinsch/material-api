# material-api

Ktor/Kotlin backend of the scout material management (Stamm inventory, kits, loans). Read README.md for the API,
schema and tenant (row-level security) conventions; PLAN.md is the current plan, REVIEW.md has the open domain
questions and the progress log.

## Related repositories

- `~/projects/material-frontend`: SvelteKit frontend. After an API change, run `UPDATE_OPENAPI=1 ./gradlew clean test`
  here, then `npm run api:generate` there.
- `~/projects/vps-deployments/material`: production stack (compose, nginx, backups), see its README.

## Live instance

`https://material.dpsg-langenbach.de`. The API isn't public; reach it through the frontend:

- Log in with the form action:
  `curl -c cj -X POST $SITE/login -H "Origin: $SITE" -H 'x-sveltekit-action: true' --data-urlencode username=… --data-urlencode password=…`
- Then call `$SITE/api/<api path>` with `-b cj`. Non-GET requests need `-H "Origin: $SITE"`. Only the paths `tenants/…`,
  `users/…` and `auth/me` are forwarded.

Demo tenant: slug `test` ("Test Stamm"), EDITOR user `test.materialwart` (ask the user for the password). Don't touch
other tenants' data.

## Local development

`docker compose up -d postgres && ./gradlew run` (API on :8080). `./gradlew test` needs Docker (Testcontainers).

## Conventions

- Code and docs in English; messages shown to users in German.
- Schema changes only as new Flyway migrations; never edit applied ones.
