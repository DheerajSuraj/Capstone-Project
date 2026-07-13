package com.tsb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TSB — Trading Strategy Builder.
 *
 * <p>Modular monolith (roadmap §3.2). Modularity is enforced in the code,
 * not the network: one package per feature under {@code com.tsb.*}, with
 * ArchUnit tests (added in Phase 2) forbidding illegal cross-package
 * dependencies — e.g. {@code execution} must never import {@code web}.
 */
@SpringBootApplication
public class TsbApplication {

    public static void main(String[] args) {
        SpringApplication.run(TsbApplication.class, args);
    }
}
