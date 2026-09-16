# ADR 0009: SFTP and bank API channels, scheduled with ShedLock

- Status: accepted
- Date: 2026-09-17

## Context

Besides upload in the browser and the REST API, banks deliver statements by dropping files on an SFTP server
or offering them through an HTTP API. Both need polling, both can see a file twice, and the application will
run with more than one instance in F4.

- An SFTP poll can see a file the bank is still uploading (B25).
- Two instances polling or downloading at the same time do the work twice (B26).
- The four channels must not drift apart: a statement has to produce the same reconciliation whichever way
  it arrived.

## Decision

**Every channel is a thin adapter in front of `ProcessStatementUseCase`.** It only turns its input into
`ImportStatementCommand(source, content)`. Format detection, size limit, balance validation, idempotency and
matching are the same code for all of them. `ChannelEquivalenceTest` sends the example files through web, REST,
SFTP and the bank API and requires identical reconciliations, recorded with their channel.

**SFTP drop (`in-sftp`).** A file is taken only when a marker `<name>.done` exists next to it; names that look
like uploads in progress (`.part`, `.tmp`, `.filepart`, ...) are never taken, even with a marker. A marker
protocol was chosen over "the file size stopped changing", which guesses, and over relying on the bank's
atomic rename, which cannot be verified from our side. If a truncated file gets a marker anyway, balance
validation rejects it (B11). After processing, files move to `processed/` or to `error/` with a
`.error.txt` stating the reason; while storage is unavailable they stay in the inbox. A crash between import
and move re-imports the file on the next poll, which B21 turns into "already imported". Only plain file names
are accepted and logs contain counts and reasons, not names (B41). Host keys are checked against a known hosts
file; accepting unknown keys is a local-only setting.

**Bank API (`out-bank-api`, `mock-bank`).** The scheduled download asks for a sliding window of recent days
(default 7) instead of remembering a cursor. Importing is idempotent, so files fetched again cost a download
and change nothing, and a statement the bank publishes late for an earlier day is still picked up. Every
failure becomes `BankUnavailableException` (B39) and is retried at the next run; one account failing does
not stop the others. `mock-bank` is a separate module with the JDK HTTP server, used by tests and local runs,
never by the application.

**Scheduling (`in-scheduler`, ShedLock).** Bank download, outbox relay and SFTP poll are `@Scheduled` methods
guarded by `@SchedulerLock`, with locks in the `shedlock` table and lock times from the database clock.
Scheduling can be switched off (`abgleich.scheduling.enabled=false`); tests do so and trigger jobs themselves,
and `ScheduledJobsLockTest` proves that a job is skipped while another instance holds its lock.

## Consequences

- Adding a channel means writing an adapter and adding it to the equivalence test, nothing in the core.
- Banks that cannot write a `.done` marker need a different readiness rule; it would be a second strategy in
  the SFTP adapter, not a change elsewhere.
- Classes with `@SchedulerLock` methods cannot be `final`, because ShedLock wraps them in a proxy.
- The embedded SFTP server and the mock bank start in milliseconds, so the end-to-end tests need Docker only
  for PostgreSQL and Kafka.
