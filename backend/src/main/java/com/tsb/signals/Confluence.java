package com.tsb.signals;

import com.tsb.compiler.Analyzer;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Diagnostic;
import com.tsb.compiler.Expr;
import com.tsb.compiler.Lexer;
import com.tsb.compiler.Parser;
import com.tsb.execution.IndicatorBank;
import com.tsb.execution.Interpreter;
import com.tsb.execution.Trade;
import com.tsb.marketdata.CandleSeries;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Module 4, part two: "were the winning trades different from the losing
 * ones?"
 *
 * <p>For every trade, we look at the market at its SIGNAL bar — the close
 * that produced the order, one bar before the fill — and record a fixed
 * panel of simple conditions (trend, momentum, volatility, volume, time of
 * day). For each condition we split the trades into "it was true" and "it
 * was false" and ask whether the win rate differs by more than chance
 * would explain.
 *
 * <p><b>Guarding against finding patterns in noise.</b> Test enough
 * conditions on the same trades and some will look good by luck. So:
 * <ul>
 *   <li>no analysis at all below {@value #MIN_TRADES} trades;</li>
 *   <li>a condition is only tested if at least {@value #MIN_PER_SIDE} trades
 *       fall on each side of it;</li>
 *   <li>each test is Fisher's exact test, and the p-values are Bonferroni
 *       corrected for the number of tests run — a finding must clear
 *       0.05 / m, not 0.05;</li>
 *   <li>even then, a finding is described as a hypothesis to re-test on
 *       other dates, never as a rule to adopt. A pattern mined from a set of
 *       trades is always flattered by those same trades.</li>
 * </ul>
 *
 * <p><b>One implementation.</b> Every market condition in the panel is
 * written in TSL and run through our own compiler, indicator bank and
 * interpreter. So the snippet we suggest adding to a strategy is exactly the
 * condition that was measured, not a re-implementation that might differ.
 *
 * <p>The conditions only read the signal bar and earlier, so they are all
 * things the strategy could actually have known when it decided.
 */
public final class Confluence {

    public static final int MIN_TRADES = 30;
    public static final int MIN_PER_SIDE = 10;
    public static final double ALPHA = 0.05;

    public enum Status { OK, TOO_FEW_TRADES }

    /**
     * One condition from the panel.
     *
     * @param tsl the condition in TSL, ready to paste; null for the time-of-day
     *            conditions, which TSL cannot express yet
     */
    public record Feature(String id, String group, String label, String tsl) {
    }

    /**
     * One condition, tested.
     *
     * @param tested        false when too few trades fell on one side to test
     * @param pValue        Fisher's exact, two-sided (null when not tested)
     * @param adjustedP     pValue × number of tests, capped at 1
     * @param significant   adjustedP below {@link #ALPHA}
     */
    public record Finding(
            Feature feature,
            int tradesWith,
            int winsWith,
            double winRateWith,
            double avgReturnWith,
            int tradesWithout,
            int winsWithout,
            double winRateWithout,
            double avgReturnWithout,
            int tradesUnknown,
            boolean tested,
            Double pValue,
            Double adjustedP,
            boolean significant,
            String suggestion,
            String sentence
    ) {
    }

    public record Report(
            Status status,
            String message,
            int trades,
            int wins,
            double winRate,
            int testsRun,
            double threshold,
            List<Finding> findings
    ) {
    }

    /** The panel. Order is display order. */
    static final List<Feature> PANEL = List.of(
            new Feature("above_sma50", "Trend", "Price above its 50-bar average",
                    "CLOSE > SMA(CLOSE, 50)"),
            new Feature("above_sma200", "Trend", "Price above its 200-bar average",
                    "CLOSE > SMA(CLOSE, 200)"),
            new Feature("sma50_above_sma200", "Trend",
                    "50-bar average above the 200-bar average",
                    "SMA(CLOSE, 50) > SMA(CLOSE, 200)"),
            new Feature("adx_trending", "Trend", "Strong trend (ADX above 25)",
                    "ADX(14) > 25"),
            new Feature("rsi_oversold", "Momentum", "RSI below 30", "RSI(14) < 30"),
            new Feature("rsi_overbought", "Momentum", "RSI above 70", "RSI(14) > 70"),
            new Feature("macd_above_signal", "Momentum", "MACD above its signal line",
                    "MACD_LINE(12, 26) > MACD_SIGNAL(12, 26, 9)"),
            new Feature("up_bar", "Momentum", "Signal bar closed higher than it opened",
                    "CLOSE > OPEN"),
            new Feature("atr_rising", "Volatility",
                    "Volatility rising (ATR above its level 10 bars earlier)",
                    "ATR(14) > ATR(14)[10]"),
            new Feature("above_bb_upper", "Volatility", "Close above the upper Bollinger band",
                    "CLOSE > BB_UPPER(CLOSE, 20, 2)"),
            new Feature("below_bb_lower", "Volatility", "Close below the lower Bollinger band",
                    "CLOSE < BB_LOWER(CLOSE, 20, 2)"),
            new Feature("volume_above_avg", "Volume", "Volume above its 20-bar average",
                    "VOLUME > SMA(VOLUME, 20)"),
            new Feature("session_asia", "Time", "Asia session (00:00–08:00 UTC)", null),
            new Feature("session_europe", "Time", "Europe session (08:00–16:00 UTC)", null),
            new Feature("session_us", "Time", "US session (16:00–24:00 UTC)", null),
            new Feature("weekend", "Time", "Weekend (Saturday or Sunday, UTC)", null));

    private Confluence() {
    }

    /** A completed position: partial exits of one entry are summed. */
    private record Position(int signalBar, double pnl, double returnPct) {
        boolean won() {
            return pnl > 0;
        }
    }

    public static Report analyze(CompiledStrategy strategy, CandleSeries series,
                                 List<Trade> trades) {
        List<Position> positions = positions(trades);
        int wins = (int) positions.stream().filter(Position::won).count();
        double winRate = SignalStatistics.pct(wins, positions.size());

        if (positions.size() < MIN_TRADES) {
            return new Report(Status.TOO_FEW_TRADES, String.format(Locale.ROOT,
                    "Only %d completed trades. Below %d, differences in win rate "
                            + "are mostly noise, so no patterns are reported. Widen "
                            + "the date range or loosen the entry to get more trades.",
                    positions.size(), MIN_TRADES),
                    positions.size(), wins, winRate, 0, ALPHA, List.of());
        }

        boolean intraday = SignalStatistics.isIntraday(series);
        // First pass: split every trade by every condition.
        List<Split> splits = new ArrayList<>();
        for (Feature f : PANEL) {
            if (f.group().equals("Time") && f.id().startsWith("session_") && !intraday) {
                continue; // daily bars all open at 00:00 — "session" is meaningless
            }
            FeatureTest test = testFor(f, strategy, series);
            splits.add(split(f, test, positions));
        }
        int m = (int) splits.stream().filter(Split::testable).count();
        double threshold = m == 0 ? ALPHA : ALPHA / m;

        List<Finding> findings = new ArrayList<>();
        for (Split s : splits) {
            findings.add(finding(s, m));
        }
        // Significant first, then by strength of evidence; untested last.
        findings.sort(Comparator
                .comparing((Finding f) -> !f.significant())
                .thenComparing(f -> !f.tested())
                .thenComparing(f -> f.pValue() == null ? 1.0 : f.pValue()));

        long significant = findings.stream().filter(Finding::significant).count();
        String message;
        if (m == 0) {
            message = "No condition had at least " + MIN_PER_SIDE
                    + " trades on both sides, so nothing could be tested.";
        } else if (significant == 0) {
            message = String.format(Locale.ROOT,
                    "Tested %d conditions on %d trades. None changed the win rate by "
                            + "more than chance would explain (each had to reach "
                            + "p < %.4f, that is 0.05 split across %d tests). That is "
                            + "a real result: none of these is worth adding as a filter.",
                    m, positions.size(), threshold, m);
        } else {
            message = String.format(Locale.ROOT,
                    "Tested %d conditions on %d trades; %d changed the win rate by more "
                            + "than chance would explain (p < %.4f after correcting for "
                            + "%d tests). These were found in the same trades they "
                            + "describe, so treat them as ideas to test, not rules: add "
                            + "one, then re-run on a date range you haven't looked at.",
                    m, positions.size(), significant, threshold, m);
        }
        return new Report(Status.OK, message, positions.size(), wins, winRate, m,
                threshold, List.copyOf(findings));
    }

    // ── Trades → positions ──────────────────────────────────────────────

    private static List<Position> positions(List<Trade> trades) {
        Map<Integer, double[]> byEntry = new LinkedHashMap<>(); // pnl, cost
        for (Trade t : trades) {
            double[] acc = byEntry.computeIfAbsent(t.entryBar(), k -> new double[2]);
            acc[0] += t.pnl();
            acc[1] += t.qty() * t.entryPrice();
        }
        List<Position> out = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : byEntry.entrySet()) {
            double cost = e.getValue()[1];
            // Orders fill at the open AFTER the signal bar.
            out.add(new Position(e.getKey() - 1, e.getValue()[0],
                    cost == 0 ? 0 : e.getValue()[0] / cost * 100.0));
        }
        return out;
    }

    // ── Evaluating a condition at a bar ─────────────────────────────────

    /** true / false / unknown (not enough history) at a bar. */
    private interface FeatureTest {
        /** 1 = true, 0 = false, -1 = unknown. */
        int at(int bar);
    }

    private static FeatureTest testFor(Feature f, CompiledStrategy strategy,
                                       CandleSeries series) {
        if (f.tsl() == null) {
            IntPredicate p = switch (f.id()) {
                case "session_asia" -> bar -> hour(series, bar) < 8;
                case "session_europe" -> bar -> hour(series, bar) >= 8 && hour(series, bar) < 16;
                case "session_us" -> bar -> hour(series, bar) >= 16;
                case "weekend" -> bar -> {
                    DayOfWeek d = utc(series, bar).getDayOfWeek();
                    return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
                };
                default -> throw new IllegalStateException("unknown time feature " + f.id());
            };
            return bar -> p.test(bar) ? 1 : 0;
        }
        CompiledStrategy probe = compileProbe(f.tsl(), strategy);
        Interpreter interp = new Interpreter(probe.lets(), series,
                IndicatorBank.compute(probe.indicators(), series));
        Expr condition = probe.rules().get(0).body().get(0).condition();
        int warmup = probe.warmupBars();
        return bar -> bar < warmup ? -1 : interp.bool(condition, bar) ? 1 : 0;
    }

    /**
     * Wraps one condition in a minimal strategy and runs it through the real
     * compiler. The warm-up the compiler works out for it is how we know at
     * which bars its answer can be trusted.
     */
    static CompiledStrategy compileProbe(String condition, CompiledStrategy base) {
        String source = "strategy \"probe\" {\n"
                + "    symbol = " + base.symbol() + "\n"
                + "    timeframe = " + base.timeframe() + "\n"
                + "    capital = 1000\n"
                + "    rule probe { IF " + condition + " THEN BUY ALL }\n"
                + "}\n";
        Lexer.LexResult lexed = new Lexer(source).scan();
        Parser.ParseResult parsed = new Parser(lexed.tokens()).parse();
        List<Diagnostic> problems = new ArrayList<>(lexed.diagnostics());
        problems.addAll(parsed.diagnostics());
        if (parsed.strategy().isPresent() && problems.stream().noneMatch(Diagnostic::isError)) {
            Analyzer.AnalysisResult analyzed = new Analyzer(parsed.strategy().get()).analyze();
            problems.addAll(analyzed.diagnostics());
            if (analyzed.strategy().isPresent()
                    && problems.stream().noneMatch(Diagnostic::isError)) {
                return analyzed.strategy().get();
            }
        }
        throw new IllegalStateException("confluence panel condition does not compile: "
                + condition + " " + problems);
    }

    private static ZonedDateTime utc(CandleSeries series, int bar) {
        return Instant.ofEpochMilli(series.openTimeMillis()[bar]).atZone(ZoneOffset.UTC);
    }

    private static int hour(CandleSeries series, int bar) {
        return utc(series, bar).getHour();
    }

    // ── Splitting and testing ───────────────────────────────────────────

    private record Split(Feature feature, int nWith, int winsWith, double retWith,
                         int nWithout, int winsWithout, double retWithout,
                         int unknown) {
        boolean testable() {
            return nWith >= MIN_PER_SIDE && nWithout >= MIN_PER_SIDE;
        }
    }

    private static Split split(Feature f, FeatureTest test, List<Position> positions) {
        int nWith = 0, winsWith = 0, nWithout = 0, winsWithout = 0, unknown = 0;
        double retWith = 0, retWithout = 0;
        for (Position p : positions) {
            int v = test.at(p.signalBar());
            if (v < 0) {
                unknown++;
            } else if (v == 1) {
                nWith++;
                retWith += p.returnPct();
                if (p.won()) {
                    winsWith++;
                }
            } else {
                nWithout++;
                retWithout += p.returnPct();
                if (p.won()) {
                    winsWithout++;
                }
            }
        }
        return new Split(f, nWith, winsWith, nWith == 0 ? 0 : retWith / nWith,
                nWithout, winsWithout, nWithout == 0 ? 0 : retWithout / nWithout,
                unknown);
    }

    private static Finding finding(Split s, int m) {
        double rateWith = SignalStatistics.pct(s.winsWith(), s.nWith());
        double rateWithout = SignalStatistics.pct(s.winsWithout(), s.nWithout());
        String label = s.feature().label();

        if (!s.testable()) {
            String sentence = String.format(Locale.ROOT,
                    "Not tested: %d trades with it and %d without — at least %d on "
                            + "each side are needed.",
                    s.nWith(), s.nWithout(), MIN_PER_SIDE);
            return new Finding(s.feature(), s.nWith(), s.winsWith(), rateWith,
                    s.retWith(), s.nWithout(), s.winsWithout(), rateWithout,
                    s.retWithout(), s.unknown(), false, null, null, false, null,
                    sentence);
        }

        double p = FisherExact.twoSided(s.winsWith(), s.nWith() - s.winsWith(),
                s.winsWithout(), s.nWithout() - s.winsWithout());
        double adjusted = Math.min(1.0, p * m);
        boolean significant = adjusted < ALPHA;
        boolean helps = rateWith > rateWithout;

        String suggestion = null;
        if (significant && s.feature().tsl() != null) {
            suggestion = helps
                    ? "AND " + s.feature().tsl()
                    : "AND NOT (" + s.feature().tsl() + ")";
        }
        String sentence = String.format(Locale.ROOT,
                "%s: %.0f%% of %d trades won when true, %.0f%% of %d when false "
                        + "(p %s; after correcting for %d tests, p %s).",
                label, rateWith, s.nWith(), rateWithout, s.nWithout(),
                fmtP(p), m, fmtP(adjusted))
                + (significant
                ? helps ? " Trades did better with it." : " Trades did worse with it."
                : " Could be chance.");
        if (significant && s.feature().tsl() == null) {
            sentence += " TSL cannot filter by time of day yet, so there is no "
                    + "snippet for this one.";
        }
        return new Finding(s.feature(), s.nWith(), s.winsWith(), rateWith,
                s.retWith(), s.nWithout(), s.winsWithout(), rateWithout,
                s.retWithout(), s.unknown(), true, p, adjusted, significant,
                suggestion, sentence);
    }

    private static String fmtP(double p) {
        return p < 0.001 ? "< 0.001" : String.format(Locale.ROOT, "= %.3f", p);
    }
}
