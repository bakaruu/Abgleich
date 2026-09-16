# ADR 0002: Gradle modules plus ArchUnit

- Status: accepted
- Date: 2026-09-16

## Context

Package conventions alone erode: one "quick" import of a JPA annotation in the domain breaks the
architecture silently (B36, B40).

## Decision

Each layer and adapter is a Gradle module, so forbidden dependencies do not compile. ArchUnit covers
what the compiler cannot: no floating point in the domain (B01), no public setters (B37), no system
clock (B38), adapters independent of each other (B40).

## Consequences

- The architecture is visible in the repository layout.
- Slightly more build configuration, shared through the `abgleich.java-conventions` plugin.
- Each ArchUnit rule has a test proving it catches a violation.
