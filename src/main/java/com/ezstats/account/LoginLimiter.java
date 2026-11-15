package com.ezstats.account;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows password guessing. A key (an address, or an address with a username) is locked for a while
 * after too many wrong tries inside a window. Keys include the address, so one person's guessing
 * can't lock a player out from everywhere else.
 */
public final class LoginLimiter {
    private static final long WINDOW_MS = 10 * 60_000L;
    private static final long LOCK_MS = 5 * 60_000L;

    private record Entry(int failures, long windowStart, long lockedUntil) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public boolean locked(String key) {
        Entry entry = entries.get(key);
        return entry != null && entry.lockedUntil() > System.currentTimeMillis();
    }

    public void failure(String key, int maxFailures) {
        long now = System.currentTimeMillis();
        if (entries.size() > 5000) entries.values().removeIf(e -> e.lockedUntil() < now && now - e.windowStart() > WINDOW_MS);
        entries.merge(key, new Entry(1, now, 0), (old, fresh) -> {
            int failures = now - old.windowStart() > WINDOW_MS ? 1 : old.failures() + 1;
            long start = failures == 1 ? now : old.windowStart();
            return new Entry(failures, start, failures >= maxFailures ? now + LOCK_MS : 0);
        });
    }

    public void success(String key) {
        entries.remove(key);
    }
}
