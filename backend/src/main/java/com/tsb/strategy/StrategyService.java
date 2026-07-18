package com.tsb.strategy;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Diagnostic;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.EquityCurves;
import com.tsb.execution.Metrics;
import com.tsb.marketdata.CandleSeries;
import com.tsb.user.User;
import com.tsb.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Strategy lifecycle: create (with version 1), append versions, run a
 * version and persist the result. The immutability invariant lives here:
 * a version is only ever INSERTED, and only if its source compiled
 * cleanly — so everything in strategy_versions is runnable, always.
 *
 * <p>Until the auth phase, all operations act as the seeded 'dev' user.
 */
@Service
public class StrategyService {

    private static final int STORED_CURVE_POINTS = 1_000;

    /** Save outcome: a version on success, diagnostics on compile failure. */
    public record SaveOutcome(
            Optional<StrategyVersion> version,
            List<Diagnostic> diagnostics
    ) {
        public boolean ok() {
            return version.isPresent();
        }
    }

    /** Run outcome: the service outcome plus the persisted run id. */
    public record RunOutcome(
            BacktestService.Outcome outcome,
            Optional<Long> runId
    ) {
    }

    private final CompilationService compilation;
    private final BacktestService backtests;
    private final StrategyRepository strategies;
    private final StrategyVersionRepository versions;
    private final BacktestRunRepository runs;
    private final UserRepository users;
    private final ObjectMapper json;

    public StrategyService(CompilationService compilation,
                           BacktestService backtests,
                           StrategyRepository strategies,
                           StrategyVersionRepository versions,
                           BacktestRunRepository runs,
                           UserRepository users,
                           ObjectMapper json) {
        this.compilation = compilation;
        this.backtests = backtests;
        this.strategies = strategies;
        this.versions = versions;
        this.runs = runs;
        this.users = users;
        this.json = json;
    }

    @Transactional
    public SaveOutcome create(String name, String source) {
        CompilationService.Outcome compiled = compilation.compile(source);
        if (!compiled.ok()) {
            return new SaveOutcome(Optional.empty(), compiled.diagnostics());
        }
        Strategy strategy = strategies.save(new Strategy(devUserId(), name));
        return new SaveOutcome(Optional.of(insertVersion(strategy, 1,
                source, compiled.strategy().orElseThrow())), List.of());
    }

    @Transactional
    public SaveOutcome addVersion(long strategyId, String source) {
        Strategy strategy = strategies.findById(strategyId).orElseThrow(() ->
                new IllegalArgumentException("no strategy " + strategyId));
        CompilationService.Outcome compiled = compilation.compile(source);
        if (!compiled.ok()) {
            return new SaveOutcome(Optional.empty(), compiled.diagnostics());
        }
        int next = versions.maxVersionNumber(strategyId) + 1;
        strategy.touch();
        strategies.save(strategy);
        return new SaveOutcome(Optional.of(insertVersion(strategy, next,
                source, compiled.strategy().orElseThrow())), List.of());
    }

    private StrategyVersion insertVersion(Strategy strategy, int number,
                                          String source, CompiledStrategy c) {
        return versions.save(new StrategyVersion(strategy.getId(), number,
                source, c.symbol(), c.timeframe(), c.warmupBars()));
    }

    /** Runs a stored version and persists the result. Reproducibility by
     *  construction: the run references the immutable version it executed. */
    @Transactional
    public RunOutcome runVersion(long strategyId, int versionNumber,
                                 Instant from, Instant to) {
        StrategyVersion version = versions
                .findByStrategyIdAndVersionNumber(strategyId, versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no version " + versionNumber + " of strategy " + strategyId));

        BacktestService.Outcome outcome =
                backtests.run(version.getSource(), from, to);
        if (!outcome.ok()) {
            return new RunOutcome(outcome, Optional.empty());
        }

        BacktestResult r = outcome.result().orElseThrow();
        CompiledStrategy strategy = outcome.strategy().orElseThrow();
        CandleSeries series = outcome.series().orElseThrow();
        Metrics metrics = Metrics.compute(r.equityCurve(), r.warmupBars(),
                r.trades(), Metrics.barsPerYear(strategy.timeframe()));

        BacktestRun run = runs.save(new BacktestRun(
                version.getId(), from, to,
                BigDecimal.valueOf(r.finalEquity()),
                r.totalReturnPct(), r.maxDrawdownPct(), r.winRate(),
                r.trades().size(), BigDecimal.valueOf(r.totalFees()),
                finiteOrNull(metrics.sharpeRatio()),
                finiteOrNull(metrics.sortinoRatio()),
                finiteOrNull(metrics.profitFactor()),
                json.writeValueAsString(r.trades()),
                json.writeValueAsString(EquityCurves.downsample(
                        series.openTimeMillis(), r.equityCurve(),
                        STORED_CURVE_POINTS))));

        return new RunOutcome(outcome, Optional.of(run.getId()));
    }

    // ── Queries ─────────────────────────────────────────────────────────

    public List<Strategy> listStrategies() {
        return strategies.findByUserIdOrderByUpdatedAtDesc(devUserId());
    }

    public Optional<Strategy> getStrategy(long id) {
        return strategies.findById(id);
    }

    public List<StrategyVersion> listVersions(long strategyId) {
        return versions.findByStrategyIdOrderByVersionNumberDesc(strategyId);
    }

    public Optional<StrategyVersion> getVersion(long strategyId, int number) {
        return versions.findByStrategyIdAndVersionNumber(strategyId, number);
    }

    public List<BacktestRun> listRuns(long strategyId) {
        return runs.findByStrategy(strategyId);
    }

    private Long devUserId() {
        return users.findByUsername("dev").map(User::getId).orElseThrow(() ->
                new IllegalStateException("dev user missing — V3 migration not applied?"));
    }

    private static Double finiteOrNull(double v) {
        return Double.isFinite(v) ? v : null;
    }
}