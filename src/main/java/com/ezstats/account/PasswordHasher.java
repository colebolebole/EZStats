package com.ezstats.account;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 with a random salt, stored as {@code pbkdf2$iterations$salt$hash}.
 *
 * <p>The iteration count follows OWASP's advice for PBKDF2-HMAC-SHA256 (600,000). It is stored with each
 * hash, so raising it later never locks anyone out: a hash made with fewer iterations still verifies, and
 * {@link #needsRehash} says to store it again, with the new count, once the right password is in hand.
 * The salt is 16 random bytes per password; the comparison is constant time.
 */
public final class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    /** A stored count outside this is a damaged or hostile file: refuse it rather than spin on it. */
    private static final int MIN_ITERATIONS = 1_000;
    private static final int MAX_ITERATIONS = 5_000_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A real hash of a random password, checked when a username is unknown so timing doesn't give it away. */
    private static final String DUMMY = hash(Base64.getEncoder().encodeToString(randomBytes(16)).toCharArray());

    private PasswordHasher() {}

    public static String hash(char[] password) {
        byte[] salt = randomBytes(16);
        byte[] key = derive(password, salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(key);
    }

    public static boolean verify(char[] password, String stored) {
        if (stored == null) stored = DUMMY;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether a stored hash was made with fewer iterations than today's: store it again after a good login. */
    public static boolean needsRehash(String stored) {
        if (stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
        try {
            return Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
