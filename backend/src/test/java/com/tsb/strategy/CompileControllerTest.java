package com.tsb.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test: boots ONLY the MVC layer for this controller — no
 * database, no Docker, no full context. Verifies the public JSON contract,
 * which is what the frontend will depend on. If any of these break after a
 * refactor, the API changed and the frontend team (future you) must know.
 *
 * <p>Note the import: Spring Boot 4 moved the web test slices to
 * {@code org.springframework.boot.webmvc.test.autoconfigure}.
 */
@WebMvcTest(CompileController.class)
@Import(CompilationService.class)
@DisplayName("POST /api/compile")
class CompileControllerTest {

    @Autowired
    private MockMvc mvc;

    private static final String VALID_SOURCE = """
            strategy "T" {
                symbol = BTCUSDT
                timeframe = 1h
                capital = 10000
                rule r { IF RSI(14) < 30 THEN BUY ALL }
            }
            """;

    /** Wraps TSL source into the request JSON, escaping it properly. */
    private static String body(String source) {
        String escaped = source
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"source\": \"" + escaped + "\"}";
    }

    @Test
    @DisplayName("valid source: 200, ok=true, summary populated")
    void validSource() throws Exception {
        mvc.perform(post("/api/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VALID_SOURCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.diagnostics").isEmpty())
                .andExpect(jsonPath("$.summary.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.summary.warmupBars").value(15))
                .andExpect(jsonPath("$.summary.indicators[0]").value("RSI(14)"));
    }

    @Test
    @DisplayName("source with an error: STILL 200, ok=false, diagnostic with span")
    void compileErrorIs200() throws Exception {
        mvc.perform(post("/api/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VALID_SOURCE.replace("RSI", "RSII"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.diagnostics[0].code").value("SEM001"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(containsString("RSI")))
                .andExpect(jsonPath("$.diagnostics[0].span.startLine").isNumber());
    }

    @Test
    @DisplayName("blank source: 400 — malformed request, not a compile result")
    void blankSourceIs400() throws Exception {
        mvc.perform(post("/api/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isBadRequest());
    }
}