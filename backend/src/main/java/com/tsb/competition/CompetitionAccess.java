package com.tsb.competition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is an admin — the "Admin" actor of the sequence diagram, who creates
 * competitions and sets their rules.
 *
 * <p>Configured by username in {@code application.yml}:
 * <pre>
 * tsb:
 *   competition:
 *     admins: dheeraj, saurav, juan
 * </pre>
 * Left empty, every signed-in user may create competitions — convenient for
 * a demo, and logged loudly at startup so it is never an accident.
 */
@Component
public class CompetitionAccess {

    private static final Logger log = LoggerFactory.getLogger(CompetitionAccess.class);

    private final Set<String> admins;

    public CompetitionAccess(@Value("${tsb.competition.admins:}") String admins) {
        this.admins = Arrays.stream(admins.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (this.admins.isEmpty()) {
            log.warn("tsb.competition.admins is empty: every signed-in user can create "
                    + "competitions. Set it to a list of usernames to restrict this.");
        }
    }

    /**
     * Forum moderators: the configured admins, and NOBODY when none are
     * configured. (Unlike creating a competition, letting every user hide
     * every other user's posts is never a sensible default.)
     */
    public boolean isModerator(String username) {
        return username != null && admins.contains(username.toLowerCase(Locale.ROOT));
    }

    public boolean isAdmin(String username) {
        return admins.isEmpty()
                || (username != null && admins.contains(username.toLowerCase(Locale.ROOT)));
    }
}
