# TSB — Trading Strategy Builder

A visual trading strategy builder with a custom DSL (**TSL**) and a
hand-written compiler, column-oriented backtesting engine, time-travel
debugger, and fair competition platform. Final Year Project.

## Repository layout

```
tsb/
├─ backend/     Spring Boot 4.1 monolith (Java 21) — compiler, engine, API
├─ frontend/    React + TS + Vite (created in Phase 4/5)
├─ ingestion/   Offline Binance archive ingestion CLI (created in Phase 1)
└─ docs/adr/    Architecture Decision Records
```

Backend is a **modular monolith**, package-by-feature under `com.tsb.*`:
`compiler` · `execution` · `marketdata` · `strategy` · `competition` ·
`user` · `common`. Module boundaries are enforced by ArchUnit tests, not
by network hops.

## Prerequisites

- JDK 21 (Eclipse Temurin) — `java -version`
- Docker Desktop (WSL2 backend) — `docker compose version`
- No Maven install needed — use the wrapper: `mvnw.cmd`

## Run locally

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Spring Boot's Docker Compose support starts Postgres 16 automatically
from `backend/compose.yaml`. Verify:

- http://localhost:8080/actuator/health → `{"status":"UP"}`

## Key invariants (do not break these)

1. The frontend generates **DSL text**, never an AST. One compiler, one
   front door.
2. Indicators are computed **once, in Java**. Never in the browser.
3. Signals fire on bar close, fill at **next bar's open**. The bar
   context cannot index past `i`.
4. `strategy_versions` is **append-only** — enforced by a DB trigger.
5. Nothing in the request path calls an external network.
6. No GPL/AGPL in `dependencies`. PineTS (AGPL) is a **CI-only** test
   oracle, never bundled or deployed.
