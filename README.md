# TSB — Trading Strategy Builder

A visual trading strategy builder with a custom DSL (**TSL**) and a
hand-written compiler, column-oriented backtesting engine, time-travel
debugger, and fair competition platform. Final Year Project.

## Repository layout

```
tsb/
├─ backend/     Spring Boot 4.1 monolith (Java 21) — compiler, engine, API
├─ frontend/    React 19 + TypeScript + Vite
├─ strategies/  Example TSL strategies
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

1. **Start the database** (Postgres 16 in Docker, on port 5433). The backend
   does not start it for you — start it once, and it keeps running:

   ```powershell
   cd backend
   docker compose up -d
   ```

2. **Set the token secret** (once per terminal, or as a user environment
   variable). It signs everyone's sign-in tokens, so it must be long, random
   and private — never commit it. `application.yml` reads it with
   `jwt-secret: ${TSB_JWT_SECRET}` (under `tsb.auth`). To make one and keep
   it for every new terminal:

   ```powershell
   $b = New-Object byte[] 48
   [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
   setx TSB_JWT_SECRET ([Convert]::ToBase64String($b))
   ```

   `setx` applies to terminals opened afterwards; for the current one, also
   run `$env:TSB_JWT_SECRET = [Convert]::ToBase64String($b)`.

3. **Start the backend:**

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   Check http://localhost:8080/actuator/health → `{"status":"UP"}`.

4. **Start the frontend** (in a second terminal):

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

   Open the address it prints (usually http://localhost:5173).

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
