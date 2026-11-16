package com.ezstats.web;

import com.ezstats.account.AccountStore;
import com.ezstats.account.LoginLimiter;
import com.ezstats.account.Nicknames;
import com.ezstats.account.PasswordHasher;
import com.ezstats.control.DeviceControl;
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
 * The website: the dashboard's static files and a small JSON API. The API is documented, with every
 * request and response, in {@code docs/api.md}.
 *
 * <ul>
 *   <li>{@code POST /api/login} a Minecraft name and the password from {@code /ezstats register}</li>
 *   <li>{@code POST /api/quicklogin} a Minecraft name and the eight digit code from {@code /ezstats quicklogin}</li>
 *   <li>{@code POST /api/reset-password} a name, the code from {@code /ezstats resetpassword} and a new password</li>
 *   <li>{@code POST /api/logout}</li>
 *   <li>{@code GET /api/state} the devices, homes and nearest weather stations of the signed-in player</li>
 *   <li>{@code GET /api/weather} every weather station and its forecast</li>
 *   <li>{@code POST /api/device/control} changes a device, through the {@link DeviceControl}</li>
 *   <li>{@code POST /api/device/nickname}, {@code /api/device/home} and {@code /api/homes...} are the owner's own data</li>
 * </ul>
 *
 * <p>Every {@code POST} needs the header {@code X-EZStats} and a JSON body of at most 4096 bytes. Keep
 * {@code docs/api.md} in step when a route, a field or a status code changes.
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
            "/app.css", new String[]{"web/app.css", "text/css; charset=utf-8"},
            "/dial.js", new String[]{"web/dial.js", "text/javascript; charset=utf-8"},
            "/devices.js", new String[]{"web/devices.js", "text/javascript; charset=utf-8"});

    private static final int MAX_USERNAME = 32;
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 128;
    private static final int MAX_CODE_INPUT = 32; // room for spaces and dashes around eight digits
    private static final int MAX_FAILS_PER_USER = 5;
    private static final int MAX_FAILS_PER_ADDRESS = 20;

    private final Logger log;
    private final LinkManager links;
    private final AccountStore accounts;
    private final LoginLimiter limiter = new LoginLimiter();
    private final SnapshotService snapshots;
    private final DeviceControl control;
    private final boolean secureCookie;
    private final boolean trustForwardedFor;
    private final boolean weatherTab;
    private final Map<String, byte[]> files = new java.util.HashMap<>();

    private HttpServer server;
    private ExecutorService executor;

    public WebServer(Logger log, LinkManager links, AccountStore accounts, SnapshotService snapshots,
                     DeviceControl control, boolean secureCookie, boolean trustForwardedFor,
                     boolean weatherTab) {
        this.log = log;
        this.links = links;
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.control = control;
        this.secureCookie = secureCookie;
        this.trustForwardedFor = trustForwardedFor;
        this.weatherTab = weatherTab;
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
            } else if (method.equals("GET") && path.equals("/api/state")) {
                state(exchange);
            } else if (method.equals("GET") && path.equals("/api/weather")) {
                weather(exchange);
            } else if (method.equals("POST") && path.equals("/api/device/control")) {
                deviceControl(exchange);
            } else if (method.equals("POST") && path.equals("/api/device/nickname")) {
                nickname(exchange);
            } else if (method.equals("POST") && path.equals("/api/device/home")) {
                deviceHome(exchange);
            } else if (method.equals("POST") && path.equals("/api/homes")) {
                homeCreate(exchange);
            } else if (method.equals("POST") && path.equals("/api/homes/rename")) {
                homeRename(exchange);
            } else if (method.equals("POST") && path.equals("/api/homes/delete")) {
                homeDelete(exchange);
            } else if (method.equals("POST") && path.equals("/api/login")) {
                login(exchange);
            } else if (method.equals("POST") && path.equals("/api/quicklogin")) {
                quickLogin(exchange);
            } else if (method.equals("POST") && path.equals("/api/reset-password")) {
                resetPassword(exchange);
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

    /**
     * Signs in with a Minecraft name and the eight digit QuickLogin code {@code /ezstats quicklogin} gave in chat.
     * Wrong tries share the limiter of password sign-in, and each also counts against the code itself,
     * which is destroyed after a few: see {@link LinkManager}. Every failure answers the same.
     */
    private void quickLogin(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("X-EZStats") == null) {
            json(exchange, 403, error("Missing X-EZStats header"));
            return;
        }
        JsonObject body = readBody(exchange);
        if (body == null) return;
        String username;
        String code;
        try {
            username = body.get("username").getAsString().trim();
            code = body.get("code").getAsString();
        } catch (RuntimeException e) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        if (username.isEmpty() || username.length() > MAX_USERNAME || code.length() > MAX_CODE_INPUT) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        // Spaces and dashes are for reading ("1234 5678"); what's left must be exactly eight digits.
        code = code.replaceAll("[\\s-]", "");
        if (!code.matches("[0-9]{" + LinkManager.CODE_DIGITS + "}")) {
            json(exchange, 400, error("The code is " + LinkManager.CODE_DIGITS + " digits."));
            return;
        }

        String ip = clientIp(exchange);
        String ipKey = "ip:" + ip;
        String userKey = "user:" + ip + "|" + username.toLowerCase(java.util.Locale.ROOT);
        if (limiter.locked(ipKey) || limiter.locked(userKey)) {
            json(exchange, 429, error("Too many tries. Wait a few minutes and try again."));
            return;
        }

        Optional<String> session = links.redeem(username, code);
        if (session.isEmpty()) {
            limiter.failure(ipKey, MAX_FAILS_PER_ADDRESS);
            limiter.failure(userKey, MAX_FAILS_PER_USER);
            json(exchange, 401, error("That code didn't work. Run /ezstats quicklogin in game for a new one."));
            return;
        }
        limiter.success(userKey);
        setSessionCookie(exchange, session.get());
        json(exchange, 200, "{\"ok\":true}");
    }

    /**
     * Sets a new password with a Minecraft name and the eight digit code {@code /ezstats resetpassword} gave in
     * chat, so the password is typed into the website and never into chat or the console. It also works for a
     * player who has no password yet. A QuickLogin code is no use here, nor this one for QuickLogin. A reset
     * signs every browser of the player out, and signs in the one that did it. Limits and failures are as
     * for {@link #quickLogin}; a password that is too short is refused before the code is spent.
     */
    private void resetPassword(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("X-EZStats") == null) {
            json(exchange, 403, error("Missing X-EZStats header"));
            return;
        }
        JsonObject body = readBody(exchange);
        if (body == null) return;
        String username;
        String code;
        String password;
        try {
            username = body.get("username").getAsString().trim();
            code = body.get("code").getAsString();
            password = body.get("password").getAsString();
        } catch (RuntimeException e) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        if (username.isEmpty() || username.length() > MAX_USERNAME || code.length() > MAX_CODE_INPUT
                || password.length() > MAX_PASSWORD) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        code = code.replaceAll("[\\s-]", "");
        if (!code.matches("[0-9]{" + LinkManager.CODE_DIGITS + "}")) {
            json(exchange, 400, error("The code is " + LinkManager.CODE_DIGITS + " digits."));
            return;
        }
        // Checked before the code is spent, so a typo in the new password doesn't cost the player their code.
        if (password.length() < MIN_PASSWORD || password.matches(".*\\s.*")) {
            json(exchange, 400, error("A password is " + MIN_PASSWORD + " to " + MAX_PASSWORD
                    + " characters, with no spaces."));
            return;
        }

        String ip = clientIp(exchange);
        String ipKey = "ip:" + ip;
        String userKey = "user:" + ip + "|" + username.toLowerCase(java.util.Locale.ROOT);
        if (limiter.locked(ipKey) || limiter.locked(userKey)) {
            json(exchange, 429, error("Too many tries. Wait a few minutes and try again."));
            return;
        }

        Optional<LinkManager.Verified> who = links.redeemCode(LinkManager.Purpose.RESET, username, code);
        if (who.isEmpty()) {
            limiter.failure(ipKey, MAX_FAILS_PER_ADDRESS);
            limiter.failure(userKey, MAX_FAILS_PER_USER);
            json(exchange, 401, error("That code didn't work. Run /ezstats resetpassword in game for a new one."));
            return;
        }
        limiter.success(userKey);
        char[] chars = password.toCharArray();
        String hash = PasswordHasher.hash(chars);
        java.util.Arrays.fill(chars, '\0');
        accounts.setPassword(who.get().player(), who.get().name(), hash);
        links.revoke(who.get().player());
        setSessionCookie(exchange, links.createSession(who.get().player(), who.get().name()));
        log.info("Password reset for " + who.get().name() + " with a code from the game.");
        json(exchange, 200, "{\"ok\":true}");
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
        // A hash made with fewer iterations than today's is stored again, stronger, now the password is known.
        if (matched != null && PasswordHasher.needsRehash(accounts.passwordHash(matched))) {
            accounts.setPassword(matched, matchedName, PasswordHasher.hash(chars));
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
        String body = "{\"player\":" + GSON.toJson(who.name()) + ",\"weatherTab\":" + weatherTab + ",\"state\":"
                + snapshots.jsonFor(accounts.view(who.player())) + "}";
        send(exchange, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Every weather station and its forecast, for any signed-in player: unlike thermostats, which belong
     * to one player, stations are the server's. An admin can switch the tab off with weather-tab.enabled.
     */
    private void weather(HttpExchange exchange) throws IOException {
        if (session(exchange).isEmpty()) {
            json(exchange, 401, error("Not signed in."));
            return;
        }
        if (!weatherTab) {
            json(exchange, 404, error("The weather tab is switched off on this server."));
            return;
        }
        send(exchange, 200, "application/json; charset=utf-8", snapshots.weatherJson().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code POST /api/device/control {key, action, value}}: changes a device through the
     * {@link DeviceControl}. Only its owner may, whatever the control behind this can do. The action is one of
     * {@code target}, {@code enabled}, {@code profile} (a thermostat); {@code powered}, {@code setpoint},
     * {@code fridge-setpoint}, {@code fan}, {@code defrost}, {@code auto-defrost} (a humidifier, purifier,
     * fridge or freezer); {@code equipment-enabled} (a heat pump, AC, furnace or boiler). A device that can't do
     * what is asked answers with EZvac's own message.
     */
    private void deviceControl(HttpExchange exchange) throws IOException {
        Call call = signedInCall(exchange);
        if (call == null) return;
        UUID player = call.session().player();
        JsonObject body = call.body();
        String key = text(body, "key");
        String action = text(body, "action");
        if (key == null || action == null) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        if (!accounts.ownerOf(key).equals(Optional.of(player))) {
            json(exchange, 403, error("That device isn't yours. Claim it in game with /ezstats own."));
            return;
        }

        DeviceControl.Result result;
        switch (action) {
            case "target" -> {
                Double value = number(body, "value");
                if (value == null || !Double.isFinite(value)) {
                    json(exchange, 400, error("Bad request"));
                    return;
                }
                result = control.setTarget(player, key, value);
            }
            case "setpoint", "fridge-setpoint" -> {
                Integer value = whole(number(body, "value"));
                if (value == null) {
                    json(exchange, 400, error("Bad request"));
                    return;
                }
                result = action.equals("setpoint") ? control.setSetpoint(player, key, value)
                        : control.setFridgeSetpoint(player, key, value);
            }
            case "profile", "fan" -> {
                String value = text(body, "value");
                if (value == null) {
                    json(exchange, 400, error("Bad request"));
                    return;
                }
                result = action.equals("profile") ? control.setProfile(player, key, value)
                        : control.setFan(player, key, value);
            }
            case "enabled", "powered", "auto-defrost", "equipment-enabled" -> {
                Boolean value = flag(body, "value");
                if (value == null) {
                    json(exchange, 400, error("Bad request"));
                    return;
                }
                result = switch (action) {
                    case "enabled" -> control.setEnabled(player, key, value);
                    case "powered" -> control.setPowered(player, key, value);
                    case "auto-defrost" -> control.setAutoDefrost(player, key, value);
                    default -> control.setEquipmentEnabled(player, key, value);
                };
            }
            case "defrost" -> result = control.startDefrost(player, key);
            default -> {
                json(exchange, 400, error("Unknown action"));
                return;
            }
        }
        if (result.ok()) {
            snapshots.refreshSoon(); // so the page sees the new value at once
            json(exchange, 200, "{\"ok\":true,\"adjusted\":" + result.adjusted() + ",\"message\":"
                    + GSON.toJson(result.message()) + "}");
        } else {
            json(exchange, result.status(), error(result.message()));
        }
    }

    /** A whole number from a JSON number, or null when it isn't one or is absurd. */
    private static Integer whole(Double value) {
        if (value == null || !Double.isFinite(value) || value != Math.rint(value) || Math.abs(value) > 10_000) return null;
        return (int) Math.rint(value);
    }

    /** A number field of a body, or null when it is missing or not a number. */
    private static Double number(JsonObject body, String field) {
        try {
            return body.getAsJsonPrimitive(field).isNumber() ? body.get(field).getAsDouble() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A true/false field of a body, or null when it is missing or not a boolean. */
    private static Boolean flag(JsonObject body, String field) {
        try {
            return body.getAsJsonPrimitive(field).isBoolean() ? body.get(field).getAsBoolean() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Sets or clears the signed-in owner's nickname for a device. This is EZStats' own data,
     * not EZvac's, so it works even while EZvac's API is read-only.
     */
    private void nickname(HttpExchange exchange) throws IOException {
        Call call = signedInCall(exchange);
        if (call == null) return;
        String key = text(call.body(), "key");
        String raw = text(call.body(), "nickname");
        if (key == null || raw == null) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        String nickname = Nicknames.clean(raw);
        if (nickname == null) {
            json(exchange, 400, error("A nickname is up to " + Nicknames.MAX_LENGTH
                    + " characters, with no control characters."));
            return;
        }
        if (!accounts.setNickname(call.session().player(), key, nickname)) {
            json(exchange, 403, error("That device isn't yours. Claim it in game with /ezstats own."));
            return;
        }
        json(exchange, 200, "{\"ok\":true}");
    }

    // --- homes: EZStats' own data, so they work while EZvac's API is read-only -----------------

    /** {@code POST /api/homes {name}} makes a home and answers with its id. */
    private void homeCreate(HttpExchange exchange) throws IOException {
        Call call = signedInCall(exchange);
        if (call == null) return;
        String name = homeName(exchange, call);
        if (name == null) return;
        AccountStore.HomeChange change = accounts.createHome(call.session().player(), call.session().name(), name);
        if (change.status() != AccountStore.HomeStatus.OK) {
            homeError(exchange, change.status());
            return;
        }
        json(exchange, 200, "{\"ok\":true,\"id\":" + GSON.toJson(change.id()) + "}");
    }

    /** {@code POST /api/homes/rename {id, name}}. */
    private void homeRename(HttpExchange exchange) throws IOException {
        Call call = signedInCall(exchange);
        if (call == null) return;
        String id = text(call.body(), "id");
        String name = id == null ? null : homeName(exchange, call);
        if (id == null) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        if (name == null) return;
        homeResult(exchange, accounts.renameHome(call.session().player(), id, name));
    }

    /** {@code POST /api/homes/delete {id}}: its devices go back to being in no home. */
    private void homeDelete(HttpExchange exchange) throws IOException {
        Call call = signedInCall(exchange);
        if (call == null) return;
        String id = text(call.body(), "id");
        if (id == null) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        homeResult(exchange, accounts.deleteHome(call.session().player(), id));
    }

    /** {@code POST /api/device/home {key, home}}: "" for no home. */
    private void deviceHome(HttpExchange exchange) throws IOException {
        Call call = signedInCall(exchange);
        if (call == null) return;
        String key = text(call.body(), "key");
        String home = text(call.body(), "home");
        if (key == null || home == null) {
            json(exchange, 400, error("Bad request"));
            return;
        }
        homeResult(exchange, accounts.setHome(call.session().player(), key, home));
    }

    /** A home's name from the body's "name", tidied. Answers the client and returns null if it isn't allowed. */
    private String homeName(HttpExchange exchange, Call call) throws IOException {
        String raw = text(call.body(), "name");
        String name = raw == null ? null : Nicknames.clean(raw);
        if (name == null || name.isEmpty()) {
            json(exchange, 400, error("A home's name is 1 to " + Nicknames.MAX_LENGTH
                    + " characters, with no control characters."));
            return null;
        }
        return name;
    }

    private void homeResult(HttpExchange exchange, AccountStore.HomeStatus status) throws IOException {
        if (status == AccountStore.HomeStatus.OK) json(exchange, 200, "{\"ok\":true}");
        else homeError(exchange, status);
    }

    private void homeError(HttpExchange exchange, AccountStore.HomeStatus status) throws IOException {
        switch (status) {
            case LIMIT -> json(exchange, 409, error("You can have up to " + AccountStore.MAX_HOMES + " homes."));
            case DUPLICATE -> json(exchange, 409, error("You already have a home with that name."));
            case NOT_FOUND -> json(exchange, 404, error("No such home."));
            default -> json(exchange, 403, error("That device isn't yours. Claim it in game with /ezstats own."));
        }
    }

    // --- shared by the calls that change something -----------------------------------------------

    private record Call(LinkManager.Session session, JsonObject body) {}

    /**
     * A POST from a signed-in browser with a small JSON body. Answers the client and returns null when
     * it isn't signed in, lacks the custom header (a cross-site form can't set one), or sent a bad body.
     */
    private Call signedInCall(HttpExchange exchange) throws IOException {
        Optional<LinkManager.Session> session = session(exchange);
        if (session.isEmpty()) {
            json(exchange, 401, error("Not signed in."));
            return null;
        }
        if (exchange.getRequestHeaders().getFirst("X-EZStats") == null) {
            json(exchange, 403, error("Missing X-EZStats header"));
            return null;
        }
        JsonObject body = readBody(exchange);
        return body == null ? null : new Call(session.get(), body);
    }

    /** A string field of a body, or null when it is missing or not a string. */
    private static String text(JsonObject body, String field) {
        try {
            return body.get(field).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
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
