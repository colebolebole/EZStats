package com.ezstats.account;

/** What counts as a nickname. */
public final class Nicknames {
    public static final int MAX_LENGTH = 32;

    private Nicknames() {}

    /**
     * Tidies what a player typed: trimmed, runs of spaces made one. Returns "" for nothing (which
     * clears a nickname) and null when it isn't allowed: too long, or it has control characters.
     */
    public static String clean(String raw) {
        if (raw == null) return "";
        String text = raw.trim().replaceAll("\\s+", " ");
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) return null;
        if (text.codePoints().anyMatch(Character::isISOControl)) return null;
        return text;
    }
}
