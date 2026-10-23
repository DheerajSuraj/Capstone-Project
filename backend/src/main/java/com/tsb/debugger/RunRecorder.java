package com.tsb.debugger;

import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.StrategyAst;
import com.tsb.execution.BarObserver;

import java.util.List;
import java.util.Optional;

/**
 * Remembers what the engine did during one backtest: whether a position was
 * open at each close, and what became of every order each statement placed.
 *
 * <p>Two small arrays instead of a trace of objects: a 5-minute run is
 * ~200k bars, and a byte per bar per statement is the cheapest honest record
 * of "what actually happened" there is.
 */
public final class RunRecorder implements BarObserver {

    private static final byte NOT_EVALUATED = 0;
    private static final byte FLAT = 1;
    private static final byte LONG = 2;

    private static final FillOutcome[] OUTCOMES = FillOutcome.values();

    /** [bar] -> NOT_EVALUATED / FLAT / LONG at that close. */
    private final byte[] position;
    /** [flat statement][signal bar] -> 0 = no order, else outcome ordinal + 1. */
    private final byte[][] outcomes;
    /** First flat-statement index of each rule. */
    private final int[] ruleOffset;
    /** [bar] -> protective settings after that close (percent; 0 = off). */
    private final float[] stopPct;
    private final float[] takeProfitPct;
    private final float[] trailingPct;
    /** [bar] -> highest price since entry, which the trailing stop follows. */
    private final double[] peak;

    public RunRecorder(CompiledStrategy strategy, int bars) {
        List<StrategyAst.RuleDecl> rules = strategy.rules();
        this.ruleOffset = new int[rules.size()];
        int total = 0;
        for (int r = 0; r < rules.size(); r++) {
            ruleOffset[r] = total;
            total += rules.get(r).body().size();
        }
        this.position = new byte[bars];
        this.outcomes = new byte[total][bars];
        this.stopPct = new float[bars];
        this.takeProfitPct = new float[bars];
        this.trailingPct = new float[bars];
        this.peak = new double[bars];
    }

    @Override
    public void onLevels(int bar, double stop, double takeProfit, double trailing,
                         double peakSinceEntry) {
        stopPct[bar] = (float) stop;
        takeProfitPct[bar] = (float) takeProfit;
        trailingPct[bar] = (float) trailing;
        peak[bar] = peakSinceEntry;
    }

    /** Stop-loss distance set after this close, percent (0 = off). */
    public double stopPct(int bar) {
        return stopPct[bar];
    }

    public double takeProfitPct(int bar) {
        return takeProfitPct[bar];
    }

    public double trailingPct(int bar) {
        return trailingPct[bar];
    }

    /** Highest price since entry, as of this close. */
    public double peakSinceEntry(int bar) {
        return peak[bar];
    }

    @Override
    public void onClose(int bar, boolean inPosition) {
        position[bar] = inPosition ? LONG : FLAT;
    }

    @Override
    public void onOrder(int signalBar, int fillBar, int ruleIndex,
                        int stmtIndex, FillOutcome outcome) {
        outcomes[ruleOffset[ruleIndex] + stmtIndex][signalBar] =
                (byte) (outcome.ordinal() + 1);
    }

    /** Whether the engine checked the rules at this bar (false in warm-up). */
    public boolean evaluated(int bar) {
        return position[bar] != NOT_EVALUATED;
    }

    /** Position at this bar's close. Only meaningful when {@link #evaluated}. */
    public boolean inPosition(int bar) {
        return position[bar] == LONG;
    }

    /** What became of the order this statement placed at this bar, if any. */
    public Optional<FillOutcome> outcome(int ruleIndex, int stmtIndex, int bar) {
        byte b = outcomes[ruleOffset[ruleIndex] + stmtIndex][bar];
        return b == 0 ? Optional.empty() : Optional.of(OUTCOMES[b - 1]);
    }
}
