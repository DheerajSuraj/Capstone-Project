package com.tsb.auth;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What counts as an acceptable password.
 *
 * <p>Length, not composition. NIST SP 800-63B dropped the "one uppercase,
 * one digit, one symbol" rule because it reliably produces {@code Password1!}
 * and little else, while rejecting genuinely strong passphrases. What it
 * recommends instead is a length floor and a check against known-breached
 * passwords, which is what this does.
 *
 * <p>The embedded list is tiny — enough to stop the handful of passwords
 * that account for a disproportionate share of real accounts. If you want
 * the real thing, the drop-in upgrade is the Have I Been Pwned range API:
 * SHA-1 the password, send the first five hex characters, and compare the
 * returned suffixes locally. The full password never leaves the server and
 * the prefix reveals nothing.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 200;

    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "passw0rd", "letmein",
            "qwertyuiop", "1234567890", "12345678910", "iloveyou",
            "administrator", "trustno1", "welcome123", "abc123456",
            "football", "baseball", "dragon123", "monkey123", "sunshine",
            "princess1", "qwerty123", "changeme", "secret123", "bitcoin",
            "bitcoin123", "trading123", "tradingview", "binance123");

    /**
     * @throws AuthException if the password is unacceptable, with a message
     *         written for the person who typed it rather than for a log.
     */
    public void check(String password, String username, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw AuthException.weakPassword(
                    "Use at least " + MIN_LENGTH + " characters. Length matters far "
                            + "more than symbols — a short phrase of ordinary words "
                            + "beats Pa$$w0rd.");
        }
        // Upper bound only to stop a megabyte of text reaching BCrypt, which
        // is deliberately slow. Note BCrypt itself silently ignores anything
        // past 72 bytes, which is why the floor matters more than the ceiling.
        if (password.length() > MAX_LENGTH) {
            throw AuthException.weakPassword("That password is too long.");
        }

        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            throw AuthException.weakPassword(
                    "That password appears in every breach list there is. Pick another.");
        }
        if (username != null && lower.contains(username.toLowerCase(Locale.ROOT))) {
            throw AuthException.weakPassword("Your password cannot contain your username.");
        }
        if (email != null && !email.isBlank()) {
            String local = email.split("@")[0].toLowerCase(Locale.ROOT);
            if (local.length() >= 4 && lower.contains(local)) {
                throw AuthException.weakPassword("Your password cannot contain your email.");
            }
        }
    }
}
