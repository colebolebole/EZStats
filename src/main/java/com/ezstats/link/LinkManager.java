package com.ezstats.link;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The link between a Minecraft player and a browser.
 *
 * <p><b>QuickLogin</b>: {@code /ezstats quicklogin} gives a player an eight digit <b>one-time code</b>, in chat. Typing it on the
 * website, with their Minecraft name, signs the browser in: it is redeemed for a <b>session</b>, 256 random
 * bits kept in a cookie. Codes and sessions live in memory only.
 *
 * <p>Eight digits are few, so a code is made hard to guess by what surrounds it rather than by its length:
 * <ul>
 *   <li>it comes from a {@link SecureRandom}, works once, and expires after a few minutes;</li>
 *   <li>a player has one code at a time, and asking for another cancels the first;</li>
 *   <li>after {@value #MAX_WRONG_GUESSES} wrong guesses the code is destroyed, whoever made them and from
 *       wherever, so one code can be guessed with a chance of at most
 *       {@value #MAX_WRONG_GUESSES} in 100 million;</li>
 *   <li>only a keyed hash of the code is kept, and it is compared in constant time;</li>
 *   <li>the web layer also limits tries by address (see {@code LoginLimiter}), and asking for codes is
 *       rate limited here.</li>
 * </ul>
 */
public final class LinkManager {
    public record Session(UUID player, String name, long expiresAtMillis) {}

    /** The digits in a code. */
    public static final int CODE_DIGITS = 8;
    /** Wrong guesses a code survives. */
    public static final int MAX_WRONG_GUESSES = 5;
    private static final long ISSUE_COOLDOWN_MS = 10_000L;

    /** A code waiting to be typed: its keyed hash, never the code itself. */
    private record Pending(UUID player, String name, byte[] mac, long expiresAtMillis, int wrongGuesses) {}

    private final SecureRandom random = new SecureRandom();
    private final byte[] macKey = new byte[32];
    private final Map<UUID, Pending> codes = new HashMap<>();      // guarded by itself
    private final Map<UUID, Long> lastIssued = new HashMap<>();    // guarded by codes
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private volatile long codeTtlMillis;
    private volatile long sessionTtlMillis;

    public LinkManager(long codeTtlSeconds, long sessionTtlHours) {
        random.nextBytes(macKey); // a fresh key each start: no code outlives the server anyway
        configure(codeTtlSeconds, sessionTtlHours);
    }

    public void configure(long codeTtlSeconds, long sessionTtlHours) {
        this.codeTtlMillis = Math.max(30, codeTtlSeconds) * 1000L;
        this.sessionTtlMillis = Math.max(1, sessionTtlHours) * 3_600_000L;
    }

    /**
     * A new one-time code for a player, as exactly {@value #CODE_DIGITS} digits. Their earlier unused code
     * stops working. Empty when they asked again too soon: see {@link #secondsUntilNextCode}.
     */
    public Optional<String> issueCode(UUID player, String name) {
        long now = System.currentTimeMillis();
        synchronized (codes) {
            purgeCodes(now);
            Long last = lastIssued.get(player);
            if (last != null && now - last < ISSUE_COOLDOWN_MS) return Optional.empty();
            String code = String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", random.nextInt(100_000_000));
            codes.put(player, new Pending(player, name, mac(player, code), now + codeTtlMillis, 0));
            lastIssued.put(player, now);
            return Optional.of(code);
        }
    }

    /** How long a code works, in seconds, as configured. */
    public long codeTtlSeconds() {
        return codeTtlMillis / 1000L;
    }

    /** How long until this player may ask for another code; 0 when they may now. */
    public long secondsUntilNextCode(UUID player) {
        synchronized (codes) {
            Long last = lastIssued.get(player);
            if (last == null) return 0;
            long left = ISSUE_COOLDOWN_MS - (System.currentTimeMillis() - last);
            return left <= 0 ? 0 : (left + 999) / 1000;
        }
    }

    /**
     * Spends a code typed with a Minecraft name. Returns the new session id, or empty for any failure
     * (no such player, no code, a wrong or expired one): the caller can't tell which, and neither can a visitor.
     * A wrong guess counts against the code, which is destroyed after {@value #MAX_WRONG_GUESSES}.
     */
    public Optional<String> redeem(String name, String code) {
        if (name == null || code == null) return Optional.empty();
        long now = System.currentTimeMillis();
        UUID winner = null;
        String winnerName = null;
        boolean any = false;
        synchronized (codes) {
            purgeCodes(now);
            for (Pending pending : codes.values().toArray(new Pending[0])) {
                if (!pending.name().equalsIgnoreCase(name)) continue;
                any = true;
                boolean right = MessageDigest.isEqual(pending.mac(), mac(pending.player(), code));
                if (right && winner == null) {
                    codes.remove(pending.player());
                    winner = pending.player();
                    winnerName = pending.name();
                } else if (!right) {
                    int wrong = pending.wrongGuesses() + 1;
                    if (wrong >= MAX_WRONG_GUESSES) codes.remove(pending.player());
                    else codes.put(pending.player(), new Pending(pending.player(), pending.name(), pending.mac(),
                            pending.expiresAtMillis(), wrong));
                }
            }
        }
        // Whatever the name, one hash was computed: nothing here times how many players have a code.
        if (!any) mac(new UUID(0L, 0L), code);
        return winner == null ? Optional.empty() : Optional.of(createSession(winner, winnerName));
    }

    /** A browser session for a player who proved who they are: with a one-time code or a password. */
    public String createSession(UUID player, String name) {
        purgeSessions();
        String id = newSecret();
        sessions.put(id, new Session(player, name, System.currentTimeMillis() + sessionTtlMillis));
        return id;
    }

    public Optional<Session> session(String id) {
        if (id == null) return Optional.empty();
        Session session = sessions.get(id);
        if (session == null) return Optional.empty();
        if (session.expiresAtMillis() < System.currentTimeMillis()) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void endSession(String id) {
        if (id != null) sessions.remove(id);
    }

    /** Signs a player out everywhere, and cancels a code they haven't used. Returns the sessions ended. */
    public int revoke(UUID player) {
        synchronized (codes) {
            codes.remove(player);
        }
        int before = sessions.size();
        sessions.values().removeIf(session -> session.player().equals(player));
        return before - sessions.size();
    }

    public int sessionCount() {
        purgeSessions();
        return sessions.size();
    }

    public int sessionCount(UUID player) {
        purgeSessions();
        return (int) sessions.values().stream().filter(session -> session.player().equals(player)).count();
    }

    /** Codes that have been given out and not used or expired. */
    public int pendingCount() {
        synchronized (codes) {
            purgeCodes(System.currentTimeMillis());
            return codes.size();
        }
    }

    // Called with `codes` held.
    private void purgeCodes(long now) {
        codes.values().removeIf(pending -> pending.expiresAtMillis() < now);
        lastIssued.values().removeIf(issued -> now - issued > ISSUE_COOLDOWN_MS);
    }

    private void purgeSessions() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> session.expiresAtMillis() < now);
    }

    /** HMAC-SHA-256 of a code for one player, under this run's secret key. */
    private byte[] mac(UUID player, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return mac.doFinal((player + ":" + code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e); // HmacSHA256 is in every Java
        }
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
