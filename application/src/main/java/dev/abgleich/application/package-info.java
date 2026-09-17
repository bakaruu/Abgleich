/**
 * Use cases and ports, grouped by capability: {@code statement}, {@code invoice}, {@code reconciliation},
 * {@code events}, {@code reporting} and {@code example}. Each has {@code port.in} (what adapters call),
 * {@code port.out} (what it needs from the outside) and {@code service} (the implementations, known only to the
 * bootstrap wiring); types both sides share sit in the capability itself. Pure Java: depends only on the domain.
 * See ADR 0011.
 */
package dev.abgleich.application;
