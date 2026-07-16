package com.tsb.marketdata;

import java.time.Duration;
import java.util.Map;

/**
 * The timeframe strings the platform ingests, and their bar durations.
 * Names match Binance's interval names exactly (both the archive folder
 * layout and the REST 'interval' parameter use them verbatim), so no
 * translation layer exists to get out of sync.
 *
 * <p>Note this is the DATA layer's list; the compiler's
 * SUPPORTED_TIMEFRAMES is the LANGUAGE's list. They overlap but answer
 * different questions ("can you say it?" vs "do we have data for it?"),
 * which is why they are deliberately separate constants.
 */
public final class Timeframes {

    public static final Map<String, Duration> SUPPORTED = Map.of(
            "5m", Duration.ofMinutes(5),
            "15m", Duration.ofMinutes(15),
            "1h", Duration.ofHours(1),
            "4h", Duration.ofHours(4)
    );

    public static Duration durationOf(String timeframe) {
        Duration d = SUPPORTED.get(timeframe);
        if (d == null) {
            throw new IllegalArgumentException("unsupported timeframe: "
                    + timeframe + " (supported: " + SUPPORTED.keySet() + ")");
        }
        return d;
    }

    private Timeframes() {
    }
}