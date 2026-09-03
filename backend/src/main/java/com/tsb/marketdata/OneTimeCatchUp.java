package com.tsb.marketdata;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OneTimeCatchUp {

    @Bean
    CommandLineRunner catchUp(IngestionService ingestion) {
        return args -> {
            String[] symbols = { "BTCUSDT", "ETHUSDT", "SOLUSDT" };
            String[] tfs     = { "5m", "15m", "1h", "4h" };
            for (String s : symbols) {
                for (String tf : tfs) {
                    int total = 0, added;
                    // keep syncing until no more bars come back — covers the
                    // whole gap even if fetchKlines returns only 1000 at a time
                    do {
                        added = ingestion.syncLatest(s, tf);
                        total += added;
                        if (added > 0) System.out.println("  " + s + " " + tf + " +" + added + " (running " + total + ")");
                    } while (added > 0);
                    System.out.println(s + " " + tf + " done -> " + total + " bars filled");
                }
            }
            System.out.println("=== catch-up complete ===");
        };
    }
}