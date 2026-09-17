# ADR 0010: Public demo on one VPS, metrics from the database

- Status: accepted
- Date: 2026-09-17

## Context

Phase F4 makes Abgleich something a stranger can open and understand in two minutes. That means a public
deployment, a Summary screen, dashboards, and protection against what the public does to a demo: uploading real
bank statements (B43) and hammering it (B44). The budget is one small VPS.

## Decision

**One VPS, one compose file.** `deploy/compose.demo.yaml` runs Caddy, the application, PostgreSQL, Kafka
(native image), Prometheus and Grafana. Only Caddy publishes ports; it gets certificates automatically and routes
`/grafana` to read-only dashboards and everything else to the application's public port. Health and metrics
listen on a separate management port (8081) that Caddy never routes. Secrets live in a `.env` next to the compose
file, required without defaults (B45). GitHub Actions builds the image after CI passed, pushes it to GitHub
Container Registry, rolls it out over SSH with a pinned host key, and runs the Playwright smoke test against the
public URL. The workflow does nothing until the deployment variables exist, so the repository stays green without a
server.

**Demo profile (B43, B44).**

- A banner on every page says the data is synthetic and deleted nightly; the upload screen offers Swiss, Spanish
  or both examples, so nobody needs their own files.
- Every night at 02:00 UTC all tables except the migration history and the job locks are truncated and the
  example is loaded again. The tables are read from the catalogue, so a table added later is purged too.
- Every ten minutes, data over 500 MB triggers the same reset: the disk quota.
- Every client address may make 20 changes (POST, PUT, PATCH, DELETE) per minute; reading is free. The limiter
  lives in memory with a bounded table, which is enough for one instance. Tomcat trusts forwarded addresses only
  from private networks such as the compose network.
- No SFTP drop and no bank download in the demo; Kafka and PostgreSQL are internal.

**Metrics from the database, not from memory.** Import counts and durations are counters and timers around the one
`ProcessStatementUseCase` every channel uses. Everything else (auto-reconciled share, review queue, unidentified
money per currency, proposals per rule and outcome, pending outbox events) is read from one repeatable-read
summary query, reused for five seconds. Several instances then report the same numbers and a restart loses
nothing. The Summary screen, `/api/v1/reports/summary` and the gauges use the same query, so they cannot disagree.
The plan's counter `abgleich_allocations_total` became the gauge `abgleich_allocations{rule,outcome}` for this
reason.

**Reviewer agreement, not accuracy.** Per rule the Summary shows how often people confirmed its proposals out of
those they decided. Proposals rejected only because another proposal for the same payment was confirmed are not
counted as disagreement. It is shown as "—" until someone decided, because 0 % and "no data" differ.

**Bug catalogue as a checked document.** `docs/bug-catalogue.md` lists B01–B45 with their protection and links to
the test classes. `BugCatalogueTest` fails the build if an ID has no test named after it or if the links and the
test sources disagree (rule 13).

## Consequences

- The demo costs one VPS and no managed services; everything can be rebuilt from the repository and a `.env`.
- The rate limit and the demo reset assume a single instance. Several instances would need a shared limiter (for
  example in Caddy or Redis); the nightly reset is already locked with ShedLock.
- Visitors can still upload a real file; the banner, the examples and the 24-hour lifetime limit the damage but
  cannot prevent it. Nothing from uploaded files is logged.
- Browser tests need a running server, so they are not part of `./gradlew build`; they run after each deployment
  and locally with `./gradlew :smoke-tests:smokeTest`.
