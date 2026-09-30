package com.tsb.competition;

import com.tsb.auth.CurrentUser;
import com.tsb.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * /api/competitions — setup (admin), joining, and the live leaderboard with
 * each entry's rule dashboard. Errors come back as {@code {message}} with
 * a 400 / 403 / 404 / 409 status, which the frontend shows as written.
 */
@RestController
@RequestMapping("/api/competitions")
@Validated
public class CompetitionController {

    private final CompetitionService service;
    private final CurrentUser currentUser;

    public CompetitionController(CompetitionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    // ── List & detail ──────────────────────────────────────────────────

    @GetMapping
    public ListResponse list() {
        User me = currentUser.require();
        List<Summary> items = service.list().stream()
                .map(c -> summary(c, me.getId())).toList();
        return new ListResponse(service.isAdmin(me), Instant.now().toString(), items);
    }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable long id) {
        User me = currentUser.require();
        Competition c = service.require(id);
        List<CompetitionEntry> mine = service.entriesOf(id, me.getId());
        Map<Long, String> labels = service.strategyLabels(
                mine.stream().map(CompetitionEntry::getStrategyVersionId).toList());
        boolean windowOpen = c.status() == Competition.Status.OPEN
                && Instant.now().isBefore(c.getStartsAt());
        return new Detail(summary(c, me.getId()), c.getDescription(), service.isAdmin(me),
                windowOpen && mine.size() < c.getMaxEntriesPerUser(),
                Instant.now().toString(),
                mine.stream().map(e -> EntryView.of(e, c, me.getUsername(),
                        labels.getOrDefault(e.getStrategyVersionId(), "Strategy"), true)).toList());
    }

    // ── Setup (admin) ──────────────────────────────────────────────────

    @PostMapping
    public Summary create(@RequestBody @Valid CreateRequest r) {
        User me = currentUser.require();
        RuleSet rules;
        try {
            rules = new RuleSet(r.startingCapital(), r.feePercent() / 100.0,
                    r.profitTargetPct(), r.maxDrawdownPct(), r.dailyLossLimitPct(),
                    r.maxTradesPerDay(), r.minTradingDays());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Instant startsAt;
        try {
            startsAt = r.startsAt() == null || r.startsAt().isBlank() ? null
                    : Instant.parse(r.startsAt());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Start time must look like 2026-10-01T09:00:00Z.");
        }
        Duration duration = Duration.ofMinutes(Math.round(r.durationHours() * 60));
        Competition c = service.create(me, new CompetitionService.CreateCommand(
                r.name(), r.description(), r.symbol(), r.timeframe(), rules,
                r.maxEntriesPerUser(), startsAt, duration));
        return summary(c, me.getId());
    }

    @PostMapping("/{id}/cancel")
    public Summary cancel(@PathVariable long id) {
        User me = currentUser.require();
        service.cancel(me, id);
        return summary(service.require(id), me.getId());
    }

    // ── Join ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/entries")
    public EntryView submit(@PathVariable long id, @RequestBody @Valid SubmitRequest r) {
        User me = currentUser.require();
        CompetitionEntry e = service.submit(me.getId(), id, r.strategyId(), r.versionNumber());
        Competition c = service.require(id);
        String label = service.strategyLabels(List.of(e.getStrategyVersionId()))
                .getOrDefault(e.getStrategyVersionId(), "Strategy");
        return EntryView.of(e, c, me.getUsername(), label, true);
    }

    // ── Leaderboard & trades ───────────────────────────────────────────

    @GetMapping("/{id}/leaderboard")
    public Leaderboard leaderboard(@PathVariable long id) {
        User me = currentUser.require();
        Competition c = service.require(id);
        CompetitionService.Board board = service.board(c);
        List<Long> userIds = board.entries().stream().map(CompetitionEntry::getUserId)
                .distinct().toList();
        Map<Long, String> names = service.usernames(userIds);
        Map<Long, String> labels = service.strategyLabels(board.entries().stream()
                .map(CompetitionEntry::getStrategyVersionId).toList());
        List<EntryView> rows = board.entries().stream().map(e -> EntryView.of(e, c,
                names.getOrDefault(e.getUserId(), "?"),
                labels.getOrDefault(e.getStrategyVersionId(), "Strategy"),
                e.getUserId().equals(me.getId()))).toList();
        List<TraderView> traders = board.traders().stream().map(t -> new TraderView(
                t.rank(), names.getOrDefault(t.userId(), "?"), t.entries(), t.passed(),
                t.eliminated(), t.cumulativePnl(),
                t.cumulativePnl() / c.rules().startingCapital() * 100,
                t.userId() == me.getId())).toList();
        return new Leaderboard(c.status().name(),
                c.getLastCandle() == null ? null : c.getLastCandle().toString(),
                Instant.now().toString(), rows, traders);
    }

    @GetMapping("/{id}/entries/{entryId}/trades")
    public List<TradeView> trades(@PathVariable long id, @PathVariable long entryId) {
        currentUser.requireId();
        return service.tradesOf(id, entryId).stream().map(TradeView::of).toList();
    }

    // ── Shapes ─────────────────────────────────────────────────────────

    private Summary summary(Competition c, long me) {
        RuleSet r = c.rules();
        return new Summary(c.getId(), c.getName(), service.tickerOf(c), c.getTimeframe(),
                c.status().name(), c.getStartsAt().toString(), c.getEndsAt().toString(),
                c.getLastCandle() == null ? null : c.getLastCandle().toString(),
                service.entryCount(c.getId()), service.entriesOf(c.getId(), me).size(),
                c.getMaxEntriesPerUser(),
                new RulesView(r.startingCapital(), r.feeFraction() * 100, r.profitTargetPct(),
                        r.maxDrawdownPct(), r.dailyLossLimitPct(), r.maxTradesPerDay(),
                        r.minTradingDays()));
    }

    public record ListResponse(boolean canCreate, String serverTime, List<Summary> competitions) {
    }

    public record RulesView(double startingCapital, double feePercent, double profitTargetPct,
                            double maxDrawdownPct, double dailyLossLimitPct,
                            int maxTradesPerDay, int minTradingDays) {
    }

    public record Summary(long id, String name, String symbol, String timeframe,
                          String status, String startsAt, String endsAt, String lastCandle,
                          long entryCount, int myEntryCount, int maxEntriesPerUser,
                          RulesView rules) {
    }

    public record Detail(Summary competition, String description, boolean isAdmin,
                         boolean canEnter, String serverTime, List<EntryView> myEntries) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description,
            @NotBlank String symbol,
            @NotBlank String timeframe,
            double startingCapital,
            double feePercent,
            double profitTargetPct,
            double maxDrawdownPct,
            double dailyLossLimitPct,
            int maxTradesPerDay,
            int minTradingDays,
            int maxEntriesPerUser,
            String startsAt,
            double durationHours
    ) {
    }

    public record SubmitRequest(@NotNull Long strategyId, @NotNull Integer versionNumber) {
    }

    /** One entry's standing and its rule dashboard. */
    public record EntryView(
            long id, Integer rank, String username, String strategy, boolean mine,
            String status, String statusReason, String statusCandle,
            String submittedAt, String sourceHash,
            double equity, double returnPct, double profitTargetPct,
            double drawdownPct, double maxDrawdownSeen, double maxDrawdownPct,
            double dailyLossPct, double dailyLossLimitPct,
            int tradesToday, int maxTradesPerDay,
            int tradingDays, int minTradingDays,
            boolean halted, String haltReason,
            boolean inTrade, double qty, double entryPrice, int tradeCount,
            String lastCandle
    ) {
        static EntryView of(CompetitionEntry e, Competition c, String username,
                            String strategy, boolean mine) {
            EntryState s = e.toState();
            RuleSet r = c.rules();
            boolean active = s.status == EntryState.Status.ACTIVE;
            int days = s.tradingDays + (active && s.tradedToday ? 1 : 0);
            return new EntryView(e.getId(), e.getRankPosition(), username, strategy, mine,
                    s.status.name(), s.statusReason,
                    s.statusCandle < 0 ? null : Instant.ofEpochMilli(s.statusCandle).toString(),
                    e.getSubmittedAt().toString(), e.getSourceHash(),
                    s.equity, s.returnPct(r), r.profitTargetPct(),
                    s.drawdownPct(), s.maxDrawdownSeen, r.maxDrawdownPct(),
                    active ? s.dailyLossPct() : 0, r.dailyLossLimitPct(),
                    active ? s.tradesToday : 0, r.maxTradesPerDay(),
                    days, r.minTradingDays(),
                    active && s.haltedToday, active ? s.haltReason : null,
                    s.inTrade(), s.qty, s.entryPrice, s.tradeCount,
                    s.lastCandle < 0 ? null : Instant.ofEpochMilli(s.lastCandle).toString());
        }
    }

    public record TraderView(int rank, String username, int entries, int passed,
                             int eliminated, double cumulativePnl, double cumulativeReturnPct,
                             boolean mine) {
    }

    public record Leaderboard(String status, String lastCandle, String serverTime,
                              List<EntryView> entries, List<TraderView> traders) {
    }

    public record TradeView(String entryTime, double entryPrice, double qty, String exitTime,
                            Double exitPrice, Double fees, Double pnl, String exitReason) {
        static TradeView of(CompetitionTrade t) {
            return new TradeView(t.getEntryTime().toString(), t.getEntryPrice(), t.getQty(),
                    t.getExitTime() == null ? null : t.getExitTime().toString(),
                    t.getExitPrice(), t.getFees(), t.getPnl(), t.getExitReason());
        }
    }
}
