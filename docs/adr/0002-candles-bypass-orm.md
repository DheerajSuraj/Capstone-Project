# ADR-0002: Candles bypass the ORM; datasets are frozen and checksummed

**Date:** 2026-07-15 · **Status:** Accepted

## Context

The platform stores two very different kinds of data:

1. **Domain data** — users, strategies, versions, runs. Few rows, rich
   relationships, read and written through business logic.
2. **Market data** — ~50k candles per symbol/timeframe, ~900k rows total.
   Written once by bulk ingestion, read as whole contiguous series by the
   backtest engine, never mutated.

Spring Data JPA is the obvious default for (1). The question is whether to
use it for (2) as well, for consistency.

Separately, the competition module claims submissions are evaluated
"fairly, on identical data". That claim needs to be verifiable, not
asserted.

## Decision

**1. Candles are not a JPA entity.** The `candles` table is written with
bulk `COPY` and read with plain `JdbcTemplate` directly into primitive
`double[]` column arrays. No `Candle` class, no repository, no Hibernate
session.

**2. Everything else uses Spring Data JPA normally.**

**3. `candles` is LIST-partitioned by `symbol_id`,** with one partition
created per symbol at ingestion time and a default partition that must
remain empty.

**4. Candle prices are `DOUBLE PRECISION`, not `NUMERIC`.** Monetary
results elsewhere (equity, PnL) remain `NUMERIC`.

**5. Backtests read frozen, checksummed `datasets`.** A dataset records the
symbol, timeframe, time range, bar count, and a SHA-256 over the canonical
candle bytes. Competition evaluation recomputes and compares the checksum
before scoring.

## Rationale

- Mapping 900k rows through Hibernate means 900k object allocations plus
  dirty-checking state for data that is immutable by definition. The
  execution engine (roadmap §8) computes over `double[]`; an ORM would
  materialise `List<Candle>` only for us to unpack it again.
- `NUMERIC` would add a conversion per value for a pipeline that computes
  in `double` regardless. Crypto prices sit far inside double's
  exact-integer range, and indicator maths is inherently approximate —
  `NUMERIC` here would be a false promise of exactness. Money columns keep
  `NUMERIC` because rounding errors in reported PnL are not acceptable.
- Every candle query filters by symbol; LIST partitioning makes that a
  single-partition scan.
- Without checksums, "all submissions were evaluated on the same data" is
  unfalsifiable. With them, it is a property anyone can re-verify from the
  published dataset id.

## Consequences

- The codebase is deliberately inconsistent about persistence style. This
  is documented here and in the migration comments so it reads as a
  decision rather than an oversight.
- Candle access lives behind a narrow repository interface in
  `com.tsb.marketdata`; no other package writes candle SQL.
- Adding a symbol requires creating its partition (handled by the
  ingestion tool, not by hand).
- The default partition must stay empty; a non-empty default partition
  indicates a missing partition and is treated as a bug.

## Alternatives considered

- **JPA for candles**: rejected on allocation cost and the array mismatch.
- **TimescaleDB**: genuinely well suited to this, but adds an extension
  dependency to every dev machine, CI, and the Oracle VM for a dataset
  that partitioned vanilla Postgres handles comfortably. Noted as future
  work if data volume grows an order of magnitude.
- **Parquet files on disk**: fast, but gives up SQL, joins to domain
  tables, and the single-backup story.
