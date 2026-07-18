package com.tsb.strategy;

import com.tsb.compiler.Diagnostic;
import com.tsb.compiler.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test of the /api/backtest contract with the service mocked —
 * the full pipeline is covered by BacktestServiceIT; here we pin the JSON
 * shapes the frontend will depend on, plus the downsampler's arithmetic.
 */
@WebMvcTest(BacktestController.class)
@DisplayName("POST /api/backtest")
class BacktestControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BacktestService backtestService;

    private static String body(String source) {
        return "{\"source\": \"" + source + "\"}";
    }

    @Test
    @DisplayName("compile failure: ok=false with diagnostics, no runError")
    void compileFailureShape() throws Exception {
        Mockito.when(backtestService.run(Mockito.anyString(),
                        Mockito.any(), Mockito.any()))
                .thenReturn(BacktestService.Outcome.compileFailure(List.of(
                        Diagnostic.error("SEM001", "unknown name 'x'",
                                Span.of(1, 2, 3)))));

        mvc.perform(post("/api/backtest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("whatever")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("SEM001"))
                .andExpect(jsonPath("$.runError").doesNotExist())
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("run failure: ok=false with runError, empty diagnostics")
    void runFailureShape() throws Exception {
        Mockito.when(backtestService.run(Mockito.anyString(),
                        Mockito.any(), Mockito.any()))
                .thenReturn(BacktestService.Outcome.runFailure(
                        "no data for symbol 'NOPEUSDT'"));

        mvc.perform(post("/api/backtest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("whatever")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.diagnostics").isEmpty())
                .andExpect(jsonPath("$.runError")
                        .value(containsString("NOPEUSDT")));
    }

    @Test
    @DisplayName("blank source: 400 via bean validation")
    void blankSource400() throws Exception {
        mvc.perform(post("/api/backtest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("garbage dates: friendly runError, not a 500")
    void badDates() throws Exception {
        mvc.perform(post("/api/backtest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\": \"x\", \"from\": \"yesterday\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runError")
                        .value(containsString("ISO-8601")));
    }

    @Test
    @DisplayName("downsampler: strides long curves, always keeps the last point")
    void downsampler() {
        int n = 2_500;
        long[] t = new long[n];
        double[] eq = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            eq[i] = 1000 + i;
        }
        var points = BacktestController.downsample(t, eq);

        org.junit.jupiter.api.Assertions.assertTrue(points.size() <= 1001);
        org.junit.jupiter.api.Assertions.assertEquals(0, points.get(0).t());
        org.junit.jupiter.api.Assertions.assertEquals(n - 1,
                points.get(points.size() - 1).t(), "last point must survive");
        org.junit.jupiter.api.Assertions.assertEquals(1000 + n - 1,
                points.get(points.size() - 1).equity(), 1e-9);
    }

    @Test
    @DisplayName("short curves pass through un-thinned")
    void shortCurveUntouched() {
        var points = BacktestController.downsample(
                new long[]{0, 1, 2}, new double[]{10, 11, 12});
        org.junit.jupiter.api.Assertions.assertEquals(3, points.size());
    }
}