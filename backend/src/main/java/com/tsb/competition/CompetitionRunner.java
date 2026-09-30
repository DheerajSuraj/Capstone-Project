package com.tsb.competition;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.execution.ExchangeRules;
import com.tsb.marketdata.CandleRepository;
import com.tsb.marketdata.CandleSeries;
import com.tsb.marketdata.Symbol;
import com.tsb.marketdata.SymbolRepository;
import com.tsb.marketdata.Timeframes;
import com.tsb.strategy.CompilationService;
import com.tsb.strategy.StrategyService;
import com.tsb.strategy.StrategyVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The "Run" and "End of competition" halves of the sequence diagram.
 *
 * <p>Called by {@link CompetitionScheduler} every 30 seconds. Each call does
 * whatever is due, so it catches up by itself after the server was off:
 * <ul>
 *   <li><b>start</b> — an OPEN competition whose start time has passed stops
 *       taking entries and starts running;</li>
 *   <li><b>run</b> — for every closed candle not yet processed: load each
 *       entry's state and locked strategy, advance it one candle under the
 *       rules, save everything at once, then rank all entries;</li>
 *   <li><b>end</b> — once the last candle is in, close open trades, decide
 *       PASSED / FAILED, save the final standings.</li>
 * </ul>
 *
 * <p>Candles come from our own table, which only ever holds CLOSED candles
 * (the ingestion job's closed-bar cutoff), so no entry ever sees a candle
 * that is still forming. Each competition is one transaction: a candle is
 * applied to every entry, or to none.
 */
@Service
public class CompetitionRunner {

    private static final Logger log = LoggerFactory.getLogger(CompetitionRunner.class);

    /** Past candles loaded before the start so indicators are warm on day one. */
    static final int MIN_WARMUP_BUFFER = 50;
    /** How long past the end to wait for the final candle before closing anyway. */
    static final Duration FINAL_CANDLE_GRACE = Duration.ofHours(1);

    private final CompetitionRepository competitions;
    private final CompetitionEntryRepository entries;
    private final CompetitionTradeRepository trades;
    private final SymbolRepository symbols;
    private final CandleRepository candles;
    private final CompilationService compiler;
    private final StrategyService strategies;

    public CompetitionRunner(CompetitionRepository competitions,
                             CompetitionEntryRepository entries,
                             CompetitionTradeRepository trades, SymbolRepository symbols,
                             CandleRepository candles, CompilationService compiler,
                             StrategyService strategies) {
        this.competitions = competitions;
        this.entries = entries;
        this.trades = trades;
        this.symbols = symbols;
        this.candles = candles;
        this.compiler = compiler;
        this.strategies = strategies;
    }

    public List<Long> dueIds() {
        return java.util.stream.Stream.concat(
                        competitions.findByStatus(Competition.Status.OPEN.name()).stream(),
                        competitions.findByStatus(Competition.Status.RUNNING.name()).stream())
                .map(Competition::getId).toList();
    }

    /** Everything due for one competition, in one transaction. */
    @Transactional
    public void advance(long competitionId) {
        Competition c = competitions.findById(competitionId).orElseThrow();
        Instant now = Instant.now();

        if (c.status() == Competition.Status.OPEN) {
            if (now.isBefore(c.getStartsAt())) {
                return; // entry window still open
            }
            start(c);
        }
        if (c.status() != Competition.Status.RUNNING) {
            return;
        }

        Duration bar = Timeframes.durationOf(c.getTimeframe());
        CandleSeries series = candles.loadBetween(c.getSymbolId(), c.getTimeframe(),
                c.getDataStart(), c.getEndsAt());
        int startIdx = firstIndexAtOrAfter(series, c.getStartsAt());
        List<CompetitionEntry> all = entries.findByCompetitionIdOrderByIdAsc(c.getId());

        if (startIdx >= 0) {
            int from = c.getLastCandle() == null ? startIdx
                    : Math.max(startIdx, firstIndexAfter(series, c.getLastCandle()));
            if (from < series.size()) {
                run(c, series, from, all);
                c.advancedTo(Instant.ofEpochMilli(series.openTimeMillis()[series.size() - 1]));
            }
        }

        boolean ended = !now.isBefore(c.getEndsAt());
        Instant lastExpected = c.getEndsAt().minus(bar);
        boolean finalCandleIn = c.getLastCandle() != null
                && !c.getLastCandle().isBefore(lastExpected);
        if (ended && (finalCandleIn || now.isAfter(c.getEndsAt().plus(FINAL_CANDLE_GRACE)))) {
            end(c, series, all);
        }
        competitions.save(c);
    }

    // ── Start: entry window closes, competition starts ─────────────────

    private void start(Competition c) {
        int maxWarmup = entries.findByCompetitionIdOrderByIdAsc(c.getId()).stream()
                .map(e -> strategies.versionById(e.getStrategyVersionId())
                        .map(StrategyVersion::getWarmupBars).orElse(0))
                .max(Integer::compare).orElse(0);
        int buffer = Math.max(MIN_WARMUP_BUFFER, maxWarmup + 1);
        Duration bar = Timeframes.durationOf(c.getTimeframe());
        c.start(c.getStartsAt().minus(bar.multipliedBy(buffer)));
        log.info("competition {} started ({} warm-up candles)", c.getId(), buffer);
    }

    // ── Run: each new candle, each entry ───────────────────────────────

    private void run(Competition c, CandleSeries series, int from,
                     List<CompetitionEntry> all) {
        RuleSet rules = c.rules();
        ExchangeRules exchange = exchangeRules(c);
        for (CompetitionEntry entry : all) {
            EntryState state = entry.toState();
            if (state.finished()) {
                continue; // eliminated earlier: skip this entry
            }
            CompetitionEngine engine = engineFor(entry, series, exchange, rules);
            for (int i = from; i < series.size() && !state.finished(); i++) {
                persist(entry.getId(), engine.step(state, i));
            }
            entry.save(state); // save all state at once
        }
        rank(c, all);
    }

    // ── End of competition ─────────────────────────────────────────────

    private void end(Competition c, CandleSeries series, List<CompetitionEntry> all) {
        RuleSet rules = c.rules();
        ExchangeRules exchange = exchangeRules(c);
        int last = series.size() - 1;
        for (CompetitionEntry entry : all) {
            EntryState state = entry.toState();
            if (state.status == EntryState.Status.ACTIVE) {
                CompetitionEngine engine = engineFor(entry, series, exchange, rules);
                persist(entry.getId(), engine.finish(state, last));
            } else if (state.status == EntryState.Status.ELIMINATED) {
                // Eliminated during the run: that is a fail, for the stated reason.
                state.status = EntryState.Status.FAILED;
                state.statusReason = "Eliminated — " + state.statusReason;
            }
            entry.save(state);
        }
        rank(c, all);
        c.finish();
        log.info("competition {} finished with {} entries", c.getId(), all.size());
    }

    // ── Shared steps ───────────────────────────────────────────────────

    /** Load the entry's locked strategy, proving it is the one submitted. */
    private CompetitionEngine engineFor(CompetitionEntry entry, CandleSeries series,
                                        ExchangeRules exchange, RuleSet rules) {
        StrategyVersion v = strategies.versionById(entry.getStrategyVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "entry " + entry.getId() + " points at a missing version"));
        if (!EntryHash.of(v.getSource()).equals(entry.getSourceHash())) {
            throw new IllegalStateException("entry " + entry.getId()
                    + " failed its integrity check: the source does not match its hash");
        }
        CompiledStrategy compiled = compiler.compile(v.getSource()).strategy()
                .orElseThrow(() -> new IllegalStateException(
                        "stored version " + v.getId() + " no longer compiles"));
        return new CompetitionEngine(compiled, series, exchange, rules);
    }

    /** "save the open trade" / "save the closed trade". */
    private void persist(long entryId, List<CompetitionEngine.Event> events) {
        for (CompetitionEngine.Event e : events) {
            switch (e) {
                case CompetitionEngine.Opened o -> trades.save(CompetitionTrade.opened(
                        entryId, Instant.ofEpochMilli(o.time()), o.price(), o.qty()));
                case CompetitionEngine.Closed cl -> {
                    var open = trades.findFirstByEntryIdAndExitTimeIsNullOrderByIdDesc(entryId);
                    if (open.isPresent()
                            && Math.abs(open.get().getQty() - cl.qty()) <= 1e-12 * Math.max(1, cl.qty())) {
                        open.get().close(cl);
                        trades.save(open.get());
                    } else {
                        // A partial exit: the sold part becomes its own
                        // finished trade, the open one keeps the rest.
                        open.ifPresent(t -> {
                            t.reduceBy(cl.qty());
                            trades.save(t);
                        });
                        trades.save(CompetitionTrade.closedPart(entryId, cl));
                    }
                }
            }
        }
    }

    /** Rank all entries and save the standings. */
    private void rank(Competition c, List<CompetitionEntry> all) {
        Map<Long, Integer> ranks = Standings.rankEntries(
                all.stream().map(CompetitionService::row).toList());
        for (CompetitionEntry e : all) {
            e.rankAs(ranks.get(e.getId()));
            entries.save(e);
        }
    }

    private ExchangeRules exchangeRules(Competition c) {
        Symbol s = symbols.findById(c.getSymbolId()).orElseThrow();
        return new ExchangeRules(s.getStepSize().doubleValue(), s.getMinNotional().doubleValue());
    }

    static int firstIndexAtOrAfter(CandleSeries s, Instant t) {
        long[] times = s.openTimeMillis();
        long target = t.toEpochMilli();
        for (int i = 0; i < times.length; i++) {
            if (times[i] >= target) {
                return i;
            }
        }
        return -1;
    }

    static int firstIndexAfter(CandleSeries s, Instant t) {
        long[] times = s.openTimeMillis();
        long target = t.toEpochMilli();
        for (int i = 0; i < times.length; i++) {
            if (times[i] > target) {
                return i;
            }
        }
        return times.length;
    }
}
