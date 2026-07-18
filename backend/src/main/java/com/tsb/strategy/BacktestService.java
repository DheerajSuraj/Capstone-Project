package com.tsb.strategy;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Diagnostic;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.Backtester;
import com.tsb.execution.ExchangeRules;
import com.tsb.marketdata.CandleRepository;
import com.tsb.marketdata.CandleSeries;
import com.tsb.marketdata.Symbol;
import com.tsb.marketdata.SymbolRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The full pipeline in one method: compile the source, resolve the symbol,
 * load the candles, run the engine. This is the moment every subsystem
 * built since Phase 0 fires together — and the same method the strategy
 * save/run flow and the competition evaluator will reuse later.
 *
 * <p><b>Two failure classes, deliberately distinct:</b> compiler problems
 * come back as {@code diagnostics} (with spans, renderable against the
 * source); platform problems — unknown symbol, no ingested data, series
 * shorter than the warm-up — come back as a {@code runError} string. The
 * strategy wasn't wrong in the second case; the platform just can't run
 * it. This is also where the analyzer's deferred promise lands: symbol
 * existence is validated HERE, against the symbols table, not in the
 * compiler.
 */
@Service
public class BacktestService {

    /** One run's outcome. Exactly one of the failure channels or the
     *  result is populated; {@code ok()} tells which. */
    public record Outcome(
            List<Diagnostic> diagnostics,
            Optional<String> runError,
            Optional<BacktestResult> result,
            Optional<CompiledStrategy> strategy,
            Optional<CandleSeries> series
    ) {
        public boolean ok() {
            return result.isPresent();
        }

        static Outcome compileFailure(List<Diagnostic> diagnostics) {
            return new Outcome(diagnostics, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
        }

        static Outcome runFailure(String error) {
            return new Outcome(List.of(), Optional.of(error), Optional.empty(),
                    Optional.empty(), Optional.empty());
        }
    }

    private final CompilationService compilation;
    private final SymbolRepository symbols;
    private final CandleRepository candles;

    public BacktestService(CompilationService compilation,
                           SymbolRepository symbols,
                           CandleRepository candles) {
        this.compilation = compilation;
        this.symbols = symbols;
        this.candles = candles;
    }

    /**
     * @param from inclusive start, or null for all history
     * @param to   exclusive end, or null for up to now
     */
    public Outcome run(String source, Instant from, Instant to) {
        CompilationService.Outcome compiled = compilation.compile(source);
        if (!compiled.ok()) {
            return Outcome.compileFailure(compiled.diagnostics());
        }
        CompiledStrategy strategy = compiled.strategy().orElseThrow();

        Optional<Symbol> symbol = symbols.findByTicker(strategy.symbol());
        if (symbol.isEmpty()) {
            return Outcome.runFailure("no data for symbol '" + strategy.symbol()
                    + "' — available symbols are seeded in the database");
        }

        CandleSeries series = (from == null && to == null)
                ? candles.loadAll(symbol.get().getId(), strategy.timeframe())
                : candles.loadBetween(symbol.get().getId(), strategy.timeframe(),
                from == null ? Instant.EPOCH : from,
                to == null ? Instant.now() : to);

        if (series.isEmpty()) {
            return Outcome.runFailure("no candles for " + strategy.symbol()
                    + " " + strategy.timeframe() + " in the requested range");
        }
        if (series.size() <= strategy.warmupBars()) {
            return Outcome.runFailure("only " + series.size() + " bars in range "
                    + "but the strategy needs " + strategy.warmupBars()
                    + " warm-up bars before it can trade — widen the range "
                    + "or shorten indicator periods");
        }

        ExchangeRules rules = new ExchangeRules(
                symbol.get().getStepSize().doubleValue(),
                symbol.get().getMinNotional().doubleValue());

        BacktestResult result = new Backtester().run(strategy, series, rules);
        return new Outcome(List.of(), Optional.empty(), Optional.of(result),
                Optional.of(strategy), Optional.of(series));
    }
}