package com.tsb.debugger;

import com.tsb.compiler.AstPrinter;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.compiler.Expr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ConditionText")
class ConditionTextTest {

    private static Expr condition(String c) {
        CompiledStrategy s = DecisionExplainerTest.compile(
                "rule a { IF " + c + " THEN BUY ALL }");
        return s.rules().get(0).body().get(0).condition();
    }

    /** Printing then re-parsing must give the same tree (AstPrinter is
     *  fully parenthesised, so equal output means equal shape). */
    private static void roundTrips(String c) {
        Expr original = condition(c);
        String printed = ConditionText.print(original);
        assertEquals(AstPrinter.print(original), AstPrinter.print(condition(printed)),
                "printed as: " + printed);
    }

    @Test
    @DisplayName("prints without redundant parentheses")
    void readable() {
        assertEquals("RSI(14) < 30 AND CLOSE > SMA(CLOSE, 200)",
                ConditionText.print(condition("(RSI(14) < 30) AND (CLOSE > SMA(CLOSE, 200))")));
        assertEquals("CLOSE > OPEN AND VOLUME > 0 AND CLOSE > 1",
                ConditionText.print(condition("CLOSE > OPEN AND VOLUME > 0 AND CLOSE > 1")));
    }

    @Test
    @DisplayName("keeps the parentheses precedence needs")
    void necessary() {
        assertEquals("(CLOSE > 1 OR CLOSE < 0) AND VOLUME > 0",
                ConditionText.print(condition("(CLOSE > 1 OR CLOSE < 0) AND VOLUME > 0")));
        assertEquals("CLOSE - (OPEN - 1) > 0",
                ConditionText.print(condition("CLOSE - (OPEN - 1) > 0")));
        assertEquals("(CLOSE + OPEN) / 2 > HIGH[1]",
                ConditionText.print(condition("(CLOSE + OPEN) / 2 > HIGH[1]")));
    }

    @Test
    @DisplayName("round-trips through the parser")
    void roundTrip() {
        roundTrips("(RSI(14) < 30 OR RSI(14) > 70) AND NOT (CLOSE < OPEN)");
        roundTrips("CLOSE - OPEN - 1 > -2 * (HIGH - LOW) / 3");
        roundTrips("CLOSE > 1 OR CLOSE > 2 AND CLOSE > 3");
        roundTrips("NOT CLOSE > OPEN OR CROSSOVER(EMA(CLOSE, 9), EMA(CLOSE, 21))");
        roundTrips("(CLOSE - CLOSE[1]) / CLOSE[1] * 100 >= 2 AND VOLUME != 0");
    }
}
