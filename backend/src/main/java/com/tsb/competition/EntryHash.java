package com.tsb.competition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * "Lock it with hash and timestamp": the SHA-256 of a strategy's source,
 * taken when the entry is submitted and checked again before every candle
 * the entry trades on. Strategy versions are already append-only, so this
 * should never fail — it is the proof, not the protection.
 */
public final class EntryHash {

    private EntryHash() {
    }

    public static String of(String source) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
