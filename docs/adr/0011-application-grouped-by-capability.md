# ADR 0011: Application grouped by capability

- Status: accepted
- Date: 2026-09-17

## Context

After F4 the `application` module held three flat packages: `port.in` with 30 files, `port.out` with 25 and
`service` with 15. Finding everything about invoices meant reading three long listings. The flat layout also hid
coupling: several `port.in` queries returned types declared inside `port.out` repositories, `in-sftp` read its size
limit from a service constant, the settlement of invoices lived in `ReconcileService` and was called from
`ReviewService`, and two services built a new invoice field by field.

## Decision

**Capabilities first, then in, out and service.** `dev.abgleich.application` has six capabilities: `statement`,
`invoice`, `reconciliation`, `events`, `reporting` and `example`. Each has `port.in` (what adapters call),
`port.out` (what the capability needs from the outside) and `service` (the implementations). Types used on both
sides, such as commands, views and results, sit in the capability package itself. Exceptions used by every
capability (`StorageException`, `StaleDataException`) and `DecisionResult` stay at the root.

**Rules that keep it that way.** ArchUnit adds three rules next to the existing ones:

- `port.in` and `port.out` never depend on each other;
- only the bootstrap wiring and the services themselves depend on `service` classes, so adapters see ports only;
- the shared types of a capability depend on no port and no service.

A capability may call another capability's `port.in` (registering an invoice starts a reconciliation), never its
service.

**Logic moved to where it belongs.**

- `Settlement.apply` in the domain applies confirmed allocations to invoices. The automatic and the manual
  confirmation share it (B22, B31).
- `RegisterInvoiceCommand.newInvoice` creates the invoice for the web, REST and Kafka channels.
- `ImportStatementUseCase.MAX_FILE_BYTES` is part of the port, so every channel can refuse a large file early (B42).

## Consequences

- Each capability reads on its own, and the package names say which side of the hexagon a type is on.
- More packages and more imports. The small capabilities (`reporting` has one class per package) look heavy, but
  they follow the same shape as the others, so nobody has to guess where a new class goes.
- The move changed no behaviour and no database schema. Bug catalogue links follow the moved test classes.
