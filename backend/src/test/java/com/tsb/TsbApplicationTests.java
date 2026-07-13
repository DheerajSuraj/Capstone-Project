package com.tsb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: verifies the Spring application context starts. If entities,
 * migrations, and configuration disagree, this fails fast — which is exactly
 * what we want. Uses the real TsbApplication as the context source.
 *
 * <p>Note: this boots the full context, so it needs a database. On CI and
 * locally with Docker running, spring-boot-docker-compose supplies Postgres.
 */
@SpringBootTest
class TsbApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: success is the context loading without error.
    }
}
