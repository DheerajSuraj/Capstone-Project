package com.tsb.competition;

import com.tsb.marketdata.Symbol;
import com.tsb.marketdata.SymbolRepository;
import com.tsb.marketdata.Timeframes;
import com.tsb.compiler.Diagnostic;
import com.tsb.strategy.CompilationService;
import com.tsb.strategy.StrategyService;
import com.tsb.strategy.StrategyVersion;
import com.tsb.user.User;
import com.tsb.user.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The "Setup" and "Join" halves of the competition sequence diagram, plus
 * the read side (competition list, leaderboard, rule dashboard). The "Run"
 * and "End" halves live in {@link CompetitionRunner}.
 */
@Service
public class CompetitionService {

    static final Duration MIN_DURATION = Duration.ofHours(1);
    static final Duration MAX_DURATION = Duration.ofDays(90);

    private final CompetitionRepository competitions;
    private final CompetitionEntryRepository entries;
    private final CompetitionTradeRepository trades;
    private final SymbolRepository symbols;
    private final UserRepository users;
    private final StrategyService strategies;
    private final CompetitionAccess access;
    private final CompilationService compiler;

    public CompetitionService(CompetitionRepository competitions,
                              CompetitionEntryRepository entries,
                              CompetitionTradeRepository trades,
                              SymbolRepository symbols, UserRepository users,
                              StrategyService strategies, CompetitionAccess access,
                              CompilationService compiler) {
        this.competitions = competitions;
        this.entries = entries;
        this.trades = trades;
        this.symbols = symbols;
        this.users = users;
        this.strategies = strategies;
        this.access = access;
        this.compiler = compiler;
    }

    // ═══ Setup ═════════════════════════════════════════════════════════

    public record CreateCommand(String name, String description, String symbol,
                                String timeframe, RuleSet rules, int maxEntriesPerUser,
                                Instant startsAt, Duration duration) {
    }

    /**
     * Admin creates a competition: sets the rules, saves the rule set, opens
     * it for entries. Start and end snap to candle boundaries, so the first
     * and last candles are whole.
     */
    @Transactional
    public Competition create(User admin, CreateCommand c) {
        if (!access.isAdmin(admin.getUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only competition admins can create competitions.");
        }
        String name = c.name() == null ? "" : c.name().trim();
        if (name.length() < 3 || name.length() > 100) {
            throw bad("Name must be 3 to 100 characters.");
        }
        Symbol symbol = symbols.findByTicker(c.symbol())
                .orElseThrow(() -> bad("Unknown symbol " + c.symbol() + "."));
        Duration bar;
        try {
            bar = Timeframes.durationOf(c.timeframe());
        } catch (IllegalArgumentException e) {
            throw bad("Unsupported timeframe " + c.timeframe() + ".");
        }
        if (c.maxEntriesPerUser() < 1 || c.maxEntriesPerUser() > 10) {
            throw bad("Entries per trader must be between 1 and 10.");
        }
        if (c.duration() == null || c.duration().compareTo(MIN_DURATION) < 0
                || c.duration().compareTo(MAX_DURATION) > 0) {
            throw bad("Duration must be between 1 hour and 90 days.");
        }
        Instant now = Instant.now();
        Instant requested = c.startsAt() == null ? now : c.startsAt();
        if (requested.isBefore(now.minusSeconds(60))) {
            throw bad("The start time is in the past. A competition only trades candles "
                    + "that did not exist when traders entered.");
        }
        Instant startsAt = ceilToBar(requested.isBefore(now) ? now : requested, bar);
        Instant endsAt = ceilToBar(startsAt.plus(c.duration()), bar);

        // A rule nobody can meet: "trade on 5 days" in a competition that only
        // touches 2 UTC days would fail every entry.
        long day = Duration.ofDays(1).toMillis();
        long days = Math.floorDiv(endsAt.toEpochMilli() - 1, day)
                - Math.floorDiv(startsAt.toEpochMilli(), day) + 1;
        if (c.rules().minTradingDays() > days) {
            throw bad("This competition covers " + days + " UTC day" + (days == 1 ? "" : "s")
                    + ", so 'minimum trading days' can be at most " + days + ".");
        }

        return competitions.save(new Competition(name, c.description(), admin.getId(),
                symbol.getId(), c.timeframe(), c.rules(), c.maxEntriesPerUser(),
                startsAt, endsAt));
    }

    /**
     * Stops a competition: before it starts, or while it runs (the way out
     * if something goes wrong mid-competition). Only the person who created
     * it, or a configured admin, may — an empty admin list lets everyone
     * CREATE competitions for a demo, but never cancel somebody else's.
     */
    @Transactional
    public void cancel(User admin, long competitionId) {
        // Locked, so a run in progress finishes its candle first, and can't
        // write RUNNING back over the cancel afterwards.
        Competition c = competitions.findLockedById(competitionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No competition " + competitionId + "."));
        if (!canCancel(admin, c)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the person who created this competition, or an admin, can cancel it.");
        }
        if (c.status() != Competition.Status.OPEN && c.status() != Competition.Status.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This competition has already ended.");
        }
        c.cancel();
        competitions.save(c);
    }

    /** Creator of the competition, or one of the configured admins. */
    public boolean canCancel(User u, Competition c) {
        return (c.getCreatedBy() != null && c.getCreatedBy().equals(u.getId()))
                || access.isModerator(u.getUsername());
    }

    // ═══ Join ══════════════════════════════════════════════════════════

    /**
     * Trader submits a strategy: check the entry window is still open, save
     * the entry, lock it with the source's hash and the time. The database
     * trigger re-checks the window, so a request racing the start time loses.
     */
    @Transactional
    public CompetitionEntry submit(long userId, long competitionId, long strategyId,
                                   int versionNumber) {
        // Locked for the whole submit: two clicks at once can't both pass the
        // entry-cap and duplicate checks, and the start can't slip in between.
        Competition c = competitions.findLockedById(competitionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No competition " + competitionId + "."));
        if (c.status() != Competition.Status.OPEN || !Instant.now().isBefore(c.getStartsAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The entry window is closed: this competition has already started.");
        }
        if (entries.countByCompetitionIdAndUserId(competitionId, userId)
                >= c.getMaxEntriesPerUser()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have the maximum of " + c.getMaxEntriesPerUser()
                            + " entries in this competition.");
        }
        StrategyVersion v = strategies.ownedVersion(userId, strategyId, versionNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No such strategy version."));
        String ticker = symbols.findById(c.getSymbolId()).map(Symbol::getTicker).orElse("?");
        if (!v.getSymbol().equals(ticker) || !v.getTimeframe().equals(c.getTimeframe())) {
            throw bad("This competition trades " + ticker + " on " + c.getTimeframe()
                    + " candles, but that strategy is for " + v.getSymbol() + " "
                    + v.getTimeframe() + ". Change its symbol and timeframe, save a new "
                    + "version, and submit that.");
        }
        // It must compile under TODAY's compiler: a version saved before a
        // compiler change could otherwise be accepted and then fail on the
        // first candle.
        CompilationService.Outcome compiled = compiler.compile(v.getSource());
        if (!compiled.ok()) {
            String first = compiled.diagnostics().stream().filter(Diagnostic::isError)
                    .map(Diagnostic::message).findFirst().orElse("it does not compile");
            throw bad("That version doesn't compile with the current version of TSB ("
                    + first + "). Open it in the builder, fix it, save a new version and "
                    + "enter that.");
        }
        String hash = EntryHash.of(v.getSource());
        List<CompetitionEntry> mine = entries.findByCompetitionIdAndUserIdOrderByIdAsc(
                competitionId, userId);
        if (mine.stream().anyMatch(e -> e.getStrategyVersionId().equals(v.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have already entered this exact version.");
        }
        // The same code saved as another version trades exactly the same,
        // so it would only fill the board (and the trader total) twice.
        if (mine.stream().anyMatch(e -> e.getSourceHash().equals(hash))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have already entered this strategy: another version you entered "
                            + "has exactly the same code.");
        }
        try {
            return entries.saveAndFlush(new CompetitionEntry(competitionId, userId, v.getId(),
                    hash, c.rules()));
        } catch (DataAccessException e) {
            // The database's own checks (the entry window trigger, the unique
            // indexes) said no: the window closed, or this went in twice.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That entry could not be saved: the entry window may have just "
                            + "closed, or it is already entered. Refresh and check.");
        }
    }

    // ═══ Reading ═══════════════════════════════════════════════════════

    public List<Competition> list() {
        return competitions.findAllByOrderByStartsAtDesc();
    }

    public Competition require(long id) {
        return competitions.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No competition " + id + "."));
    }

    public String tickerOf(Competition c) {
        return symbols.findById(c.getSymbolId()).map(Symbol::getTicker).orElse("?");
    }

    public long entryCount(long competitionId) {
        return entries.countByCompetitionId(competitionId);
    }

    public List<CompetitionEntry> entriesOf(long competitionId) {
        return entries.findByCompetitionIdOrderByIdAsc(competitionId);
    }

    public List<CompetitionEntry> entriesOf(long competitionId, long userId) {
        return entries.findByCompetitionIdAndUserIdOrderByIdAsc(competitionId, userId);
    }

    public List<CompetitionTrade> tradesOf(long competitionId, long entryId) {
        CompetitionEntry e = entries.findById(entryId)
                .filter(x -> x.getCompetitionId().equals(competitionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No such entry."));
        return trades.findByEntryIdOrderByEntryTimeAscIdAsc(e.getId());
    }

    public boolean isAdmin(User u) {
        return access.isAdmin(u.getUsername());
    }

    /** Usernames for a set of user ids. */
    public Map<Long, String> usernames(List<Long> ids) {
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
    }

    /** "My strategy v3" labels — names only, never the source. */
    public Map<Long, String> strategyLabels(List<Long> versionIds) {
        Map<Long, String> out = new HashMap<>();
        for (Long id : versionIds) {
            strategies.versionById(id).ifPresent(v -> out.put(id,
                    strategies.strategyName(v.getStrategyId()).orElse("Strategy")
                            + " v" + v.getVersionNumber()));
        }
        return out;
    }

    /** Standings in rank order, plus the cumulative trader table. */
    public record Board(List<CompetitionEntry> entries, List<Standings.TraderScore> traders) {
    }

    public Board board(Competition c) {
        List<CompetitionEntry> all = entriesOf(c.getId());
        List<Standings.Row> rows = all.stream().map(CompetitionService::row).toList();
        Map<Long, Integer> ranks = Standings.rankEntries(rows);
        Map<Long, CompetitionEntry> byId = all.stream()
                .collect(Collectors.toMap(CompetitionEntry::getId, Function.identity()));
        List<CompetitionEntry> ordered = new ArrayList<>();
        ranks.keySet().forEach(id -> ordered.add(byId.get(id)));
        return new Board(ordered, Standings.rankTraders(rows, c.rules().startingCapital()));
    }

    static Standings.Row row(CompetitionEntry e) {
        EntryState s = e.toState();
        return new Standings.Row(e.getId(), e.getUserId(), s.status, s.equity,
                e.getSubmittedAt().toEpochMilli());
    }

    static Instant ceilToBar(Instant t, Duration bar) {
        long ms = bar.toMillis();
        long v = t.toEpochMilli();
        long up = Math.floorDiv(v + ms - 1, ms) * ms;
        return Instant.ofEpochMilli(up);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static final Comparator<Competition> NEWEST_FIRST =
            Comparator.comparing(Competition::getStartsAt).reversed();
}
