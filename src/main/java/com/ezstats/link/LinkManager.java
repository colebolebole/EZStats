package com.ezstats.link;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The link between a Minecraft player and a browser.
 *
 * <p>{@code /ezstats link} makes a one-time <b>token</b>. Opening the link redeems it for a
 * <b>session</b>, which the browser keeps in a cookie. Both are 256 random bits, kept in memory only.
 */
public final class LinkManager {
    public record Session(UUID player, String name, long expiresAtMillis) {}

    private record Pending(UUID player, String name, long expiresAtMillis) {}

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> tokens = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private volatile long tokenTtlMillis;
    private volatile long sessionTtlMillis;

    public LinkManager(long tokenTtlSeconds, long sessionTtlHours) {
        configure(tokenTtlSeconds, sessionTtlHours);
    }

    public void configure(long tokenTtlSeconds, long sessionTtlHours) {
        this.tokenTtlMillis = Math.max(10, tokenTtlSeconds) * 1000L;
        this.sessionTtlMillis = Math.max(1, sessionTtlHours) * 3_600_000L;
    }

    /** A new one-time token for a player. An earlier unused token of theirs stops working. */
    public String createToken(UUID player, String name) {
        purge();
        tokens.values().removeIf(pending -> pending.player().equals(player));
        String token = newSecret();
        tokens.put(token, new Pending(player, name, System.currentTimeMillis() + tokenTtlMillis));
        return token;
    }

    /** Spends a token. Returns the new session id, or empty when the token is unknown, used or expired. */
    public Optional<String> redeem(String token) {
        if (token == null) return Optional.empty();
        Pending pending = tokens.remove(token);
        if (pending == null || pending.expiresAtMillis() < System.currentTimeMillis()) return Optional.empty();
        String id = newSecret();
        sessions.put(id, new Session(pending.player(), pending.name(),
                System.currentTimeMillis() + sessionTtlMillis));
        return Optional.of(id);
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

    /** Signs a player out everywhere, and cancels a link they haven't opened. Returns the sessions ended. */
    public int revoke(UUID player) {
        tokens.values().removeIf(pending -> pending.player().equals(player));
        int before = sessions.size();
        sessions.values().removeIf(session -> session.player().equals(player));
        return before - sessions.size();
    }

    public int sessionCount() {
        purge();
        return sessions.size();
    }

    public int sessionCount(UUID player) {
        purge();
        return (int) sessions.values().stream().filter(session -> session.player().equals(player)).count();
    }

    public int pendingCount() {
        purge();
        return tokens.size();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(pending -> pending.expiresAtMillis() < now);
        sessions.values().removeIf(session -> session.expiresAtMillis() < now);
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
