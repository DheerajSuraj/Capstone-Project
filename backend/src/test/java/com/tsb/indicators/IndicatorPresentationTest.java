package com.tsb.indicators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tsb.compiler.Registry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drift guard.
 *
 * <p>{@code IndicatorBank} already relies on a test like this to keep the
 * registry and its dispatch switch in step. Presentation is the third place an
 * indicator has to be named, so it gets the same treatment: adding indicator
 * #29 to the registry should fail the build until it can also be drawn, rather
 * than quietly missing from the picker for a term.
 */
class IndicatorPresentationTest {

    private static List<String> precomputedNames() {
        return Registry.allNames().stream()
                .filter(n -> Registry.lookup(n).orElseThrow().precomputed())
                .toList();
    }

    @Test
    @DisplayName("every indicator the engine computes can also be drawn")
    void everyIndicatorHasALook() {
        for (String name : precomputedNames()) {
            assertTrue(IndicatorPresentation.TABLE.containsKey(name),
                    "'" + name + "' is in the registry but has no entry in "
                            + "IndicatorPresentation — add one so it appears "
                            + "in the chart's indicator picker");
        }
    }

    @Test
    @DisplayName("presentation names nothing the registry does not have")
    void noOrphanedLooks() {
        for (String name : IndicatorPresentation.TABLE.keySet()) {
            assertTrue(Registry.lookup(name).isPresent(),
                    "IndicatorPresentation has an entry for '" + name
                            + "', which is not in the Registry");
            assertTrue(Registry.lookup(name).orElseThrow().precomputed(),
                    "'" + name + "' is a function, not a precomputed "
                            + "indicator, so it has no series to draw");
        }
    }

    @Test
    @DisplayName("default values match the parameters the registry declares")
    void defaultsLineUpWithParameters() {
        for (String name : precomputedNames()) {
            var look = IndicatorPresentation.TABLE.get(name);
            var sig = Registry.lookup(name).orElseThrow();

            long constParams = sig.params().stream()
                    .filter(p -> p.kind() == Registry.ParamKind.CONST_NUMBER)
                    .count();

            assertEquals(constParams, look.defaults().size(),
                    "'" + name + "' takes " + constParams + " numeric "
                            + "argument(s) but presentation supplies "
                            + look.defaults().size() + " default(s)");

            boolean needsSource = sig.params().stream()
                    .anyMatch(p -> p.kind() == Registry.ParamKind.PRICE_SERIES);
            if (needsSource) {
                assertTrue(look.defaultSource() != null && !look.defaultSource().isBlank(),
                        "'" + name + "' takes a price series but presentation "
                                + "gives no default source");
            }
        }
    }

    @Test
    @DisplayName("periods default to whole numbers of at least one")
    void periodDefaultsAreUsable() {
        for (String name : precomputedNames()) {
            var look = IndicatorPresentation.TABLE.get(name);
            var sig = Registry.lookup(name).orElseThrow();

            int i = 0;
            for (Registry.Param p : sig.params()) {
                if (p.kind() != Registry.ParamKind.CONST_NUMBER) continue;
                double value = look.defaults().get(i++);
                if (p.positiveInt()) {
                    assertTrue(value >= 1 && value == Math.floor(value),
                            name + "." + p.name() + " must default to a whole "
                                    + "number of 1 or more, got " + value);
                }
            }
        }
    }

    @Test
    @DisplayName("companions point at real indicators, both ways")
    void companionsAreMutual() {
        for (var entry : IndicatorPresentation.TABLE.entrySet()) {
            for (String companion : entry.getValue().companions()) {
                var other = IndicatorPresentation.TABLE.get(companion);
                assertTrue(other != null,
                        entry.getKey() + " names companion '" + companion
                                + "', which has no presentation entry");
                assertTrue(other.companions().contains(entry.getKey()),
                        entry.getKey() + " names '" + companion + "' as a "
                                + "companion but not the other way round — "
                                + "adding either should add both");
                assertFalse(companion.equals(entry.getKey()),
                        entry.getKey() + " lists itself as a companion");
            }
        }
    }
}