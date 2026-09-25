package com.tsb.signals;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Span;
import com.tsb.compiler.StrategyAst;
import com.tsb.debugger.ConditionNode;
import com.tsb.debugger.DecisionExplainer;
import com.tsb.debugger.Explanation;
import com.tsb.debugger.RunRecorder;
import com.tsb.execution.BarObserver;
import com.tsb.marketdata.CandleSeries;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Module 4, part one: how each condition behaved over the whole run.
 *
 * <p>The debugger answers "why at THIS bar?". This answers "why so rarely?"
 * — across every bar the engine checked: how often each condition and each
 * part of it was true, how many of its signals actually became trades and
 * why the rest didn't, how often it nearly fired, and which part most often
 * stood alone between the strategy and a signal.
 *
 * <p>It is built on the debugger, not beside it. Every number here is a
 * count over {@link DecisionExplainer#measureCondition} results and the
 * engine's own {@link RunRecorder} — so a bar the statistics count as "true"
 * is a bar the debugger will show as true, and one the engine traded on.
 */
public final class SignalStatistics {

    /** Default near-miss threshold: within 2% of flipping. */
    public static final double DEFAULT_NEAR_MISS = 0.02;

    public record Report(
            int barsEvaluated,
            double nearMissThreshold,
            /** False for daily-and-above data, where hour-of-day means nothing. */
            boolean intraday,
            List<StatementStats> statements
    ) {
    }

    /**
     * @param trueBars      bars where the condition was true
     * @param unknownBars   bars where it was false only because a value was undefined
     * @param actionBars    bars where the engine was asked for an order or a SET
     * @param outcomes      what became of those — FILLED, IGNORED_..., per the debugger
     * @param nearMisses    false bars where one part, moved by at most the
     *                      threshold, would have made it true
     * @param bottleneck    the part that most often stood alone in the way
     *                      (null if nothing ever did)
     * @param trueByHour    24 counts, UTC hour of the bar's open
     * @param barsByHour    how many evaluated bars fell in each hour (the denominators)
     * @param trueByWeekday 7 counts, Monday first
     * @param barsByWeekday denominators, Monday first
     */
    public record StatementStats(
            int ruleIndex,
            String ruleName,
            int statementIndex,
            Span span,
            String condition,
            String thenAction,
            int trueBars,
            double truePct,
            int unknownBars,
            int actionBars,
            Map<Explanation.Outcome, Integer> outcomes,
            int nearMisses,
            List<LeafStats> parts,
            String bottleneck,
            int[] trueByHour,
            int[] barsByHour,
            int[] trueByWeekday,
            int[] barsByWeekday,
            String sentence
    ) {
    }

    /**
     * One comparison or crossing inside a condition.
     *
     * @param soleBlockerBars bars where the whole condition was false and
     *                        this part being true would have been enough
     */
    public record LeafStats(
            String text,
            Span span,
            int trueBars,
            double truePct,
            int unknownBars,
            int soleBlockerBars
    ) {
    }

    private SignalStatistics() {
    }

    public static Report compute(DecisionExplainer explainer, double nearMissThreshold) {
        CompiledStrategy strategy = explainer.strategy();
        CandleSeries series = explainer.series();
        RunRecorder recorder = explainer.recorder();
        int n = series.size();

        boolean intraday = isIntraday(series);
        int evaluated = 0;
        for (int i = 0; i < n; i++) {
            if (recorder.evaluated(i)) {
                evaluated++;
            }
        }

        List<StatementStats> out = new ArrayList<>();
        List<StrategyAst.RuleDecl> rules = strategy.rules();
        for (int r = 0; r < rules.size(); r++) {
            List<StrategyAst.IfStmt> body = rules.get(r).body();
            for (int k = 0; k < body.size(); k++) {
                out.add(statement(explainer, r, k, evaluated, nearMissThreshold));
            }
        }
        return new Report(evaluated, nearMissThreshold, intraday, List.copyOf(out));
    }

    private static StatementStats statement(DecisionExplainer explainer, int r,
                                            int k, int evaluated,
                                            double threshold) {
        CandleSeries series = explainer.series();
        RunRecorder recorder = explainer.recorder();
        StrategyAst.RuleDecl rule = explainer.strategy().rules().get(r);
        StrategyAst.IfStmt stmt = rule.body().get(k);
        int n = series.size();

        int trueBars = 0;
        int unknownBars = 0;
        int actionBars = 0;
        int nearMisses = 0;
        Map<Explanation.Outcome, Integer> outcomes =
                new EnumMap<>(Explanation.Outcome.class);
        int[] trueByHour = new int[24];
        int[] barsByHour = new int[24];
        int[] trueByWeekday = new int[7];
        int[] barsByWeekday = new int[7];

        List<String> leafText = null;
        List<Span> leafSpan = null;
        int[] leafTrue = null;
        int[] leafUnknown = null;
        int[] leafSole = null;
        String conditionText = null;

        for (int i = 0; i < n; i++) {
            if (!recorder.evaluated(i)) {
                continue;
            }
            ConditionNode root = explainer.measureCondition(stmt.condition(), i);
            List<ConditionNode> leaves = new ArrayList<>();
            DecisionExplainer.collectLeaves(root, leaves);
            if (leafText == null) { // the tree has the same shape at every bar
                conditionText = root.text();
                leafText = new ArrayList<>();
                leafSpan = new ArrayList<>();
                for (ConditionNode leaf : leaves) {
                    leafText.add(leaf.text());
                    leafSpan.add(leaf.span());
                }
                leafTrue = new int[leaves.size()];
                leafUnknown = new int[leaves.size()];
                leafSole = new int[leaves.size()];
            }

            ZonedDateTime when = Instant.ofEpochMilli(series.openTimeMillis()[i])
                    .atZone(ZoneOffset.UTC);
            int hour = when.getHour();
            int weekday = when.getDayOfWeek().getValue() - 1;
            barsByHour[hour]++;
            barsByWeekday[weekday]++;

            for (int j = 0; j < leaves.size(); j++) {
                ConditionNode leaf = leaves.get(j);
                if (leaf.passed()) {
                    leafTrue[j]++;
                }
                if (leaf.unknown()) {
                    leafUnknown[j]++;
                }
            }

            if (root.passed()) {
                trueBars++;
                trueByHour[hour]++;
                trueByWeekday[weekday]++;
            } else {
                if (root.unknown()) {
                    unknownBars++;
                }
                for (int j = 0; j < leaves.size(); j++) {
                    ConditionNode leaf = leaves.get(j);
                    if (!leaf.passed() && DecisionExplainer.flips(root, leaf)) {
                        leafSole[j]++;
                    }
                }
                Explanation.ClosestChange closest = DecisionExplainer.closestChange(root);
                if (closest != null && closest.relativeDistance() != null
                        && closest.relativeDistance() <= threshold) {
                    nearMisses++;
                }
            }

            StrategyAst.Action action = root.passed() ? stmt.thenAction()
                    : stmt.elseAction().orElse(null);
            if (action != null) {
                actionBars++;
                Explanation.Outcome o = outcomeOf(action, recorder, r, k, i, n);
                outcomes.merge(o, 1, Integer::sum);
            }
        }

        List<LeafStats> parts = new ArrayList<>();
        String bottleneck = null;
        int bottleneckBars = 0;
        if (leafText != null) {
            for (int j = 0; j < leafText.size(); j++) {
                parts.add(new LeafStats(leafText.get(j), leafSpan.get(j),
                        leafTrue[j], pct(leafTrue[j], evaluated), leafUnknown[j],
                        leafSole[j]));
                if (leafSole[j] > bottleneckBars) {
                    bottleneckBars = leafSole[j];
                    bottleneck = leafText.get(j);
                }
            }
        }
        if (conditionText == null) {
            conditionText = "";
        }

        String thenText = describeAction(stmt.thenAction());
        String sentence = sentence(trueBars, evaluated, actionBars, outcomes,
                nearMisses, threshold, bottleneck, bottleneckBars, parts.size());

        return new StatementStats(r, rule.name(), k, stmt.span(), conditionText,
                thenText, trueBars, pct(trueBars, evaluated), unknownBars,
                actionBars, outcomes, nearMisses, List.copyOf(parts), bottleneck,
                trueByHour, barsByHour, trueByWeekday, barsByWeekday, sentence);
    }

    private static Explanation.Outcome outcomeOf(StrategyAst.Action action,
                                                 RunRecorder recorder, int r,
                                                 int k, int bar, int n) {
        if (action instanceof StrategyAst.Action.Set) {
            return Explanation.Outcome.SETTING_APPLIED;
        }
        if (bar == n - 1) {
            return Explanation.Outcome.NOT_FILLED_LAST_BAR;
        }
        BarObserver.FillOutcome f = recorder.outcome(r, k, bar).orElseThrow(
                () -> new IllegalStateException("engine placed no order at bar "
                        + bar + " for a statement that asked for one"));
        return switch (f) {
            case FILLED -> Explanation.Outcome.FILLED;
            case IGNORED_ALREADY_LONG -> Explanation.Outcome.IGNORED_ALREADY_LONG;
            case IGNORED_NOTHING_TO_SELL -> Explanation.Outcome.IGNORED_NOTHING_TO_SELL;
            case REJECTED_TOO_SMALL -> Explanation.Outcome.REJECTED_TOO_SMALL;
        };
    }

    private static String sentence(int trueBars, int evaluated, int actionBars,
                                   Map<Explanation.Outcome, Integer> outcomes,
                                   int nearMisses, double threshold,
                                   String bottleneck, int bottleneckBars,
                                   int leafCount) {
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT, "True on %.1f%% of bars (%,d of %,d).",
                pct(trueBars, evaluated), trueBars, evaluated));
        if (actionBars > 0) {
            int filled = outcomes.getOrDefault(Explanation.Outcome.FILLED, 0);
            int applied = outcomes.getOrDefault(Explanation.Outcome.SETTING_APPLIED, 0);
            int ignoredLong = outcomes.getOrDefault(Explanation.Outcome.IGNORED_ALREADY_LONG, 0);
            int ignoredFlat = outcomes.getOrDefault(Explanation.Outcome.IGNORED_NOTHING_TO_SELL, 0);
            int small = outcomes.getOrDefault(Explanation.Outcome.REJECTED_TOO_SMALL, 0);
            if (applied > 0) {
                s.append(String.format(Locale.ROOT, " Applied a setting %,d times.", applied));
            }
            if (filled + ignoredLong + ignoredFlat + small > 0) {
                s.append(String.format(Locale.ROOT, " Of the orders it placed (THEN and ELSE), %,d filled", filled));
                if (ignoredLong > 0) {
                    s.append(String.format(Locale.ROOT,
                            ", %,d were ignored because a position was already open", ignoredLong));
                }
                if (ignoredFlat > 0) {
                    s.append(String.format(Locale.ROOT,
                            ", %,d were ignored because there was nothing to sell", ignoredFlat));
                }
                if (small > 0) {
                    s.append(String.format(Locale.ROOT,
                            ", %,d were too small for the exchange", small));
                }
                s.append('.');
            }
        }
        if (nearMisses > 0) {
            s.append(String.format(Locale.ROOT,
                    " It nearly fired (one part within %.0f%%) on %,d more bars.",
                    threshold * 100, nearMisses));
        }
        if (bottleneck != null && leafCount > 1) {
            s.append(String.format(Locale.ROOT,
                    " The part most often standing alone in the way: %s (%,d bars).",
                    bottleneck, bottleneckBars));
        }
        return s.toString();
    }

    private static String describeAction(StrategyAst.Action a) {
        return switch (a) {
            case StrategyAst.Action.Buy b -> "BUY";
            case StrategyAst.Action.Sell s -> "SELL";
            case StrategyAst.Action.Set s -> "SET " + s.target();
        };
    }

    /** Intraday iff bars open at more than one hour of the day. */
    static boolean isIntraday(CandleSeries series) {
        long[] t = series.openTimeMillis();
        if (t.length == 0) {
            return false;
        }
        long first = Math.floorMod(t[0], 86_400_000L);
        for (long x : t) {
            if (Math.floorMod(x, 86_400_000L) != first) {
                return true;
            }
        }
        return false;
    }

    static double pct(int part, int whole) {
        return whole == 0 ? 0 : part * 100.0 / whole;
    }
}
