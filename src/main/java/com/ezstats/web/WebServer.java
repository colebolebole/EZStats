package com.ezstats.web;

import com.ezstats.account.AccountStore;
import com.ezstats.account.LoginLimiter;
import com.ezstats.account.Nicknames;
import com.ezstats.account.PasswordHasher;
import com.ezstats.control.ThermostatControl;
import com.ezstats.link.LinkManager;
import com.ezstats.snapshot.SnapshotService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * The website: the dashboard's three static files and a small JSON API.
 *
 * <ul>
 *   <li>{@code GET /link?token=} spends a link token, sets the session cookie and goes to {@code /}</li>
 *   <li>{@code POST /api/login} a Minecraft name and the password from {@code /ezstats register}</li>
 *   <li>{@code GET /api/state} the thermostats the signed-in player owns</li>
 *   <li>{@code POST /api/thermostat/target} and {@code /enabled}, through the {@link ThermostatControl}</li>
 *   <li>{@code POST /api/logout}</li>
 * </ul>
 */
public final class WebServer {
    static final String COOKIE = "ezstats_session";
    private static final int MAX_BODY = 4096;
    private static final Gson GSON = new Gson();

    /** The static files, by path; read once from the jar. */
    private static final Map<String, String[]> STATIC = Map.of(
            "/", new String[]{"web/index.html", "text/html; charset=utf-8"},
            "/index.html", new String[]{"web/index.html", "text/html; charset=utf-8"},
            "/app.js", new String[]{"web/app.js", "text/javascript; charset=utf-8"},
            "/app.css", new String[]{"web/app.css", "text/css; charset=utf-8"});

    private static final int MAX_USERNAME = 32;
    private static final int MAX_PASSWORD = 128;
    private static final int MAX_FAILS_PER_USER = 5;
    private static final int MAX_FAILS_PER_ADDRESS = 20;

    private final Logger log;
    private final LinkManager links;
    private final AccountStore accounts;
    private final LoginLimiter limiter = new LoginLimiter();
    private final SnapshotService snapshots;
    private final ThermostatControl control;
    private final boolean secureCookie;
    private final boolean trustForwardedFor;
    private final Map<String, byte[]> files = new java.util.HashMap<>();

    private HttpServer server;
    private ExecutorService executor;

    public WebServer(Logger log, LinkManager links, AccountStore accounts, SnapshotService snapshots,
                     ThermostatControl control, boolean secureCookie, boolean trustForwardedFor) {
        this.log = log;
        this.links = links;
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.control = control;
        this.secureCookie = secureCookie;
        this.trustForwardedFor = trustForwardedFor;
    }

    public void start(String bind, int port) throws IOException {
        for (String[] entry : STATIC.values()) {
            if (files.containsKey(entry[0])) continue;
            try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(entry[0])) {
                if (in == null) throw new IOException("Missing resource " + entry[0]);
                files.put(entry[0], in.readAllBytes());
            }
        }
        AtomicInteger counter = new AtomicInteger();
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "EZStats-web-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        server = null;
        executor = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && STATIC.containsKey(path)) {
                String[] entry = STATIC.get(path);
                send(exchange, 200, entry[1], files.get(entry[0]));
            } else if (method.equals("GET") && path.equals("/link")) {
                link(exchange);
            } else if (method.equals("GET") && path.equals("/api/state")) {
                state(exchange);
            } else if (method.equals("POST") && path.equals("/api/thermostat/target")) {
                change(exchange, true);
            } else if (method.equals("POST") && path.equals("/api/thermostat/enabled")) {
                change(exchange, false);
            } else if (method.equals("POST") && path.equals("/api/thermostat/nickname")) {
                nickname(exchange);
            } else if (method.equals("POST") && path.equals("/api/login")) {
                login(exchange);
            } else if (method.equals("POST") && path.equals("/api/logout")) {
                logout(exchange);
            } else {
                json(exchange, 404, error("Not found"));
            }
        } catch (RuntimeException e) {
            log.warning("Web request failed: " + e);
            json(exchange, 500, error("Something went wrong"));
        } finally {
            exchange.close();
        }
    }

    private void link(HttpExchange exchange) throws IOException {
        String token = query(exchange, "token");
        Optional<String> session = links.redeem(token);
        if (session.isEmpty()) {
            send(exchange, 410, "text/html; charset=utf-8", ("<!doctype html><meta charset=utf-8>"
                    + "<meta name=viewport content='width=device-width,initial-scale=1'><title>EZStats</title>"
                    + "<body style='font-family:system-ui;max-width:32rem;margin:4rem auto;padding:0 1rem'>"
                    + "<h1>This link has expired</h1><p>Links work once, for a few minutes. "
                    + "Run <code>/ezstats link</code> in game for a new one.</p>").getBytes(StandardCharsets.UTF_8));
            return;
        }
        setSessionCookie(exchange, session.get());
        exchange.getResponseHeaders().add("Location", "/");
        send(exchange, 302, "text/plain", new byte[0]);
    }

    private void setSessionCookie(HttpExchange exchange, String sessionId) {
        exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + sessionId
                + "; Path=/; HttpOnly; SameSite=Strict" + (secureCookie ? "; Secure" : ""));
    }

    /** Signs in with a Minecraft name and the password set with {@code /ezstats register}. */
    private void login(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("X-EZStats") == null) {
            json(exchange, 403, error("Missing X-EZStats header"));
            return;
        }
        JsonObject body = readBody(exchange);
        if (body == null) return;
        String username;
        String password;
        try {
            username = body.get("username").getAsString().trim();
            password = body.get("password").getAsString();
        } catch (RuntimeException e) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        if (username.isEmpty() || username.length() > MAX_USERNAME || password.isEmpty()
                || password.length() > MAX_PASSWORD) {
            json(exchange, 400, error("Bad request"));
            return;
        }

        String ip = clientIp(exchange);
        String ipKey = "ip:" + ip;
        String userKey = "user:" + ip + "|" + username.toLowerCase(java.util.Locale.ROOT);
        if (limiter.locked(ipKey) || limiter.locked(userKey)) {
            json(exchange, 429, error("Too many tries. Wait a few minutes and try again."));
            return;
        }

        // Every path does one PBKDF2 check, with a dummy hash for an unknown name, so timing
        // doesn't tell a visitor which names have accounts.
        char[] chars = password.toCharArray();
        UUID matched = null;
        String matchedName = null;
        java.util.List<UUID> candidates = accounts.loginCandidates(username);
        if (candidates.isEmpty()) {
            PasswordHasher.verify(chars, null);
        } else {
            for (UUID candidate : candidates) {
                if (PasswordHasher.verify(chars, accounts.passwordHash(candidate))) {
                    matched = candidate;
                    matchedName = accounts.name(candidate);
                    break;
                }
            }
        }
        java.util.Arrays.fill(chars, '\0');

        if (matched == null) {
            limiter.failure(ipKey, MAX_FAILS_PER_ADDRESS);
            limiter.failure(userKey, MAX_FAILS_PER_USER);
            json(exchange, 401, error("Wrong username or password."));
            return;
        }
        limiter.success(userKey);
        setSessionCookie(exchange, links.createSession(matched, matchedName));
        json(exchange, 200, "{\"ok\":true}");
    }

    private String clientIp(HttpExchange exchange) {
        if (trustForwardedFor) {
            // A proxy adds the address it saw to the end of the list; anything before it is the client's claim.
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                return parts[parts.length - 1].trim();
            }
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    /** Reads a small JSON body. Answers the client and returns null when it is too big or not JSON. */
    private JsonObject readBody(HttpExchange exchange) throws IOException {
        try {
            byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) {
                json(exchange, 413, error("Too large"));
                return null;
            }
            JsonObject body = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
            if (body == null) throw new JsonParseException("empty");
            return body;
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            json(exchange, 400, error("Bad request"));
            return null;
        }
    }

    private void state(HttpExchange exchange) throws IOException {
        Optional<LinkManager.Session> session = session(exchange);
        if (session.isEmpty()) {
            json(exchange, 401, error("Not signed in."));
            return;
        }
        LinkManager.Session who = session.get();
        String body = "{\"player\":" + GSON.toJson(who.name()) + ",\"state\":"
                + snapshots.jsonFor(accounts.ownedWithNicknames(who.player())) + "}";
        send(exchange, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void change(HttpExchange exchange, boolean target) throws IOException {
        Optional<LinkManager.Session> session = session(exchange);
        if (session.isEmpty()) {
            json(exchange, 401, error("Not linked. Run /ezstats link in game."));
            return;
        }
        // A cross-site form can't set a custom header without a preflight, which this server never grants.
        if (exchange.getRequestHeaders().getFirst("X-EZStats") == null) {
            json(exchange, 403, error("Missing X-EZStats header"));
            return;
        }
        JsonObject body = readBody(exchange);
        if (body == null) return;
        ThermostatControl.Result result;
        try {
            String key = body.get("key").getAsString();
            // Only the owner may change a thermostat, whatever the control behind this can do.
            if (!accounts.ownerOf(key).equals(Optional.of(session.get().player()))) {
                json(exchange, 403, error("That thermostat isn't yours. Claim it in game with /ezstats own."));
                return;
            }
            result = target
                    ? control.setTarget(session.get().player(), key, body.get("targetF").getAsDouble())
                    : control.setEnabled(session.get().player(), key, body.get("enabled").getAsBoolean());
        } catch (RuntimeException e) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        if (result.ok()) json(exchange, 200, "{\"ok\":true}");
        else json(exchange, result.status(), error(result.message()));
    }

    /**
     * Sets or clears the signed-in owner's nickname for a thermostat. This is EZStats' own data,
     * not EZvac's, so it works even while EZvac's API is read-only.
     */
    private void nickname(HttpExchange exchange) throws IOException {
        Optional<LinkManager.Session> session = session(exchange);
        if (session.isEmpty()) {
            json(exchange, 401, error("Not signed in."));
            return;
        }
        if (exchange.getRequestHeaders().getFirst("X-EZStats") == null) {
            json(exchange, 403, error("Missing X-EZStats header"));
            return;
        }
        JsonObject body = readBody(exchange);
        if (body == null) return;
        String key;
        String raw;
        try {
            key = body.get("key").getAsString();
            raw = body.get("nickname").getAsString();
        } catch (RuntimeException e) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        String nickname = Nicknames.clean(raw);
        if (nickname == null) {
            json(exchange, 400, error("A nickname is up to " + Nicknames.MAX_LENGTH
                    + " characters, with no control characters."));
            return;
        }
        if (!accounts.setNickname(session.get().player(), key, nickname)) {
            json(exchange, 403, error("That thermostat isn't yours. Claim it in game with /ezstats own."));
            return;
        }
        json(exchange, 200, "{\"ok\":true}");
    }

    private void logout(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("X-EZStats") == null) {
            json(exchange, 403, error("Missing X-EZStats header"));
            return;
        }
        links.endSession(cookie(exchange));
        exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        json(exchange, 200, "{\"ok\":true}");
    }

    private Optional<LinkManager.Session> session(HttpExchange exchange) {
        return links.session(cookie(exchange));
    }

    private static String cookie(HttpExchange exchange) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(COOKIE + "=")) return trimmed.substring(COOKIE.length() + 1);
            }
        }
        return null;
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String error(String message) {
        return "{\"error\":" + GSON.toJson(message) + "}";
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; "
                + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
