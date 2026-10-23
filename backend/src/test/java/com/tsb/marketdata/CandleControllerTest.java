package com.tsb.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CandleController.class)
@DisplayName("GET /api/candles")
class CandleControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SymbolRepository symbols;
    @MockitoBean
    private CandleRepository candles;

    @Test
    @DisplayName("returns columnar arrays for a known symbol")
    void columnarShape() throws Exception {
        Symbol sym = Mockito.mock(Symbol.class);
        Mockito.when(sym.getId()).thenReturn(1L);
        Mockito.when(symbols.findByTicker("BTCUSDT")).thenReturn(Optional.of(sym));
        Mockito.when(candles.loadLatest(Mockito.eq(1L), Mockito.eq("1h"),
                        Mockito.any(), Mockito.any(), Mockito.eq(CandleController.MAX_BARS + 1)))
                .thenReturn(new CandleSeries(
                        new long[]{1000, 2000},
                        new double[]{10, 11}, new double[]{12, 13},
                        new double[]{9, 10}, new double[]{11, 12},
                        new double[]{100, 200}));

        mvc.perform(get("/api/candles")
                        .param("symbol", "BTCUSDT")
                        .param("timeframe", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.t[0]").value(1000))
                .andExpect(jsonPath("$.c[1]").value(12.0))
                .andExpect(jsonPath("$.v[1]").value(200.0))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    @DisplayName("an unknown timeframe is 400, not an empty chart")
    void unknownTimeframe400() throws Exception {
        Symbol sym = Mockito.mock(Symbol.class);
        Mockito.when(symbols.findByTicker("BTCUSDT")).thenReturn(Optional.of(sym));
        mvc.perform(get("/api/candles")
                        .param("symbol", "BTCUSDT")
                        .param("timeframe", "7m"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("more bars than the cap: the newest come back, marked truncated")
    void truncatedKeepsNewest() throws Exception {
        Symbol sym = Mockito.mock(Symbol.class);
        Mockito.when(sym.getId()).thenReturn(1L);
        Mockito.when(symbols.findByTicker("BTCUSDT")).thenReturn(Optional.of(sym));
        int n = CandleController.MAX_BARS + 1;
        long[] t = new long[n];
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 3_600_000L;
            x[i] = i;
        }
        Mockito.when(candles.loadLatest(Mockito.eq(1L), Mockito.eq("1h"),
                        Mockito.any(), Mockito.any(), Mockito.eq(n)))
                .thenReturn(new CandleSeries(t, x, x, x, x, x));

        mvc.perform(get("/api/candles")
                        .param("symbol", "BTCUSDT")
                        .param("timeframe", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.t.length()").value(CandleController.MAX_BARS))
                .andExpect(jsonPath("$.c[0]").value(1.0)); // the oldest bar was dropped
    }

    @Test
    @DisplayName("unknown symbol is 404")
    void unknownSymbol404() throws Exception {
        Mockito.when(symbols.findByTicker("NOPE")).thenReturn(Optional.empty());
        mvc.perform(get("/api/candles")
                        .param("symbol", "NOPE")
                        .param("timeframe", "1h"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("garbage dates are 400")
    void badDates400() throws Exception {
        Symbol sym = Mockito.mock(Symbol.class);
        Mockito.when(symbols.findByTicker("BTCUSDT")).thenReturn(Optional.of(sym));
        mvc.perform(get("/api/candles")
                        .param("symbol", "BTCUSDT")
                        .param("timeframe", "1h")
                        .param("from", "yesterday"))
                .andExpect(status().isBadRequest());
    }
}