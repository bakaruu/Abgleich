# ADR 0012: One correlation id per unit of work

- Status: accepted
- Date: 2026-09-18

## Context

The application writes JSON logs, but nothing tied them together. A visitor who says "my upload failed" gave no way
to find the lines that belong to their file, and a file that arrives through the SFTP drop while three people work
in the browser produced interleaved lines with nothing to separate them. The import id existed in the database and
in the API response, but never in a log line.

## Decision

**Every unit of work carries one id, in the SLF4J MDC, for as long as it runs.** A unit of work is one HTTP request,
one file taken from the SFTP drop, one Kafka message or one scheduled run.

- HTTP: `CorrelationIdFilter` accepts an `X-Correlation-Id` header when it matches `[A-Za-z0-9._-]{1,64}`, otherwise
  it mints one, and always returns it in the response. The header is untrusted input: anything else would let a
  caller forge log lines or push personal data into the logs (B32, B41). The filter runs before everything else,
  including the rate limiter, so even a refused request is logged with an id the visitor can quote.
- Kafka: the same header on the record, under the same rules, so an invoice can be followed from the ERP into this
  system; otherwise `kafka-<random>`.
- SFTP: one id per file, not per poll, so the lines about a rejected file are not mixed with the ones about its
  neighbours.
- Scheduled jobs: one id per run, prefixed with the job name (`outbox-relay-<random>`).

**The import id is an MDC field too.** `LoggedProcessStatement`, a decorator in the wiring module beside
`MeteredProcessStatement`, writes one line per file with the source, format, outcome and counts, with the import ids
of the file in `import.id`. The use case itself stays free of logging.

**Dotted MDC keys, because the logs are ECS.** `correlation.id` and `import.id` are nested by the ECS formatter into
`"correlation":{"id":...}` and `"import":{"id":...}`, which is what log tooling expects.

**Each entry point sets the key itself.** Adapters share no code (B40), so the constant is repeated in the three
adapters that need it rather than introducing a module for eleven lines.

## Consequences

- One request, one file or one job can be followed end to end: `correlation.id` finds everything it caused, and
  `import.id` finds everything about one stored file, across restarts and across instances.
- Only ids and counts are logged. Nothing read from a bank file reaches a log line, and `Iban.toString()` stays
  masked (B41), which the decorator's tests check.
- A caller that supplies its own id gets it back and can join both systems' logs without a shared tracing backend.
  Full distributed tracing (OpenTelemetry spans) would be the next step, and these ids would become its baggage.
- The MDC is thread-bound: work handed to another thread would lose the id. Everything here runs on the thread that
  started it.
