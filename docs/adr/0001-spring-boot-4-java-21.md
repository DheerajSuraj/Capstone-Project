# ADR-0001: Spring Boot 4.1 on Java 21

**Date:** 2026-07-13 · **Status:** Accepted

## Context

The architecture roadmap (v1.0) specifies "Spring Boot 3.x, Java 21".
Since it was written, Spring Boot 3.5 — the last 3.x line — reached
open-source end of life on 2026-06-30. The currently supported lines are
4.0.x (until 2026-12-31) and 4.1.x (until 2027-07-31). Starting a new
project on an EOL framework line would contradict the project's own
dependency-hygiene claims (roadmap §2).

## Decision

- **Spring Boot 4.1.0** (Spring Framework 7), generated via start.spring.io.
- **Java 21 (LTS, Eclipse Temurin).** All language features the compiler
  design depends on — sealed interfaces, records, exhaustive
  pattern-matching `switch` — are final in 21. Java 25 (newer LTS) was
  considered and rejected: no feature we need, and 21 is what college lab
  machines, CI images, and the Oracle ARM free tier reliably provide.
- Maven with the wrapper (`mvnw`) pinned in-repo; no globally installed
  Maven, so all team members and CI build with the identical version.

## Consequences

- 4.1.x is in OSS support through the project's full lifetime, including
  the viva.
- Spring Boot 4 renamed/split some starter artifacts vs 3.x; we treat
  start.spring.io output as the authoritative source of coordinates
  rather than copying 3.x-era tutorials.
- Spring Security 7 idioms differ slightly from older tutorials; auth is
  built in Phase 4 against current 4.1 documentation.

## Related deliberate exclusions (Phase 0)

- **Spring Security** deferred to Phase 4 (auth phase) — avoids locking
  every endpoint during compiler development.
- **Lombok** excluded permanently — Java 21 records cover the need.
- **Caffeine, JJWT, ta4j** added only in the phase that uses them, with
  versions verified on the day of addition (ta4j must be pinned to a
  Java-21-compatible release; current master targets Java 25+).
