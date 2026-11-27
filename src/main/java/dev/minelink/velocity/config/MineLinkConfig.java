package dev.minelink.velocity.config;

import com.moandjiezana.toml.Toml;
import dev.minelink.velocity.relay.Upstream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public record MineLinkConfig(
    Map<String, Upstream> upstreams,
    List<String> order,
    int upstreamTimeoutMs,
    int staggerMs,
    int deadlineMs,
    DirectBridgeConfig directBridge
) {

    public static final Map<String, Upstream> BUILTIN_UPSTREAMS;
    private static final Pattern UPSTREAM_ID = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final List<String> DEFAULT_ORDER = List.of("minehut", "minekeep");
    private static final Set<String> KNOWN_KEYS = Set.of(
        "order",
        "upstream-timeout-ms",
        "stagger-ms",
        "deadline-ms",
        "upstreams",
        "direct-bridge"
    );
    private static final Set<String> KNOWN_BRIDGE_KEYS = Set.of(
        "enabled",
        "bind",
        "target"
    );

    static {
        Map<String, Upstream> map = new LinkedHashMap<>();
        map.put("minehut", new Upstream(
            "minehut",
            "https://api.minehut.com/mitm/proxy/session/minecraft/hasJoined"
        ));
        map.put("minekeep", new Upstream(
            "minekeep",
            "https://sessionserver.minekeep.net/session/minecraft/hasJoined"
        ));
        map.put("mojang", new Upstream(
            "mojang",
            "https://sessionserver.mojang.com/session/minecraft/hasJoined"
        ));
        BUILTIN_UPSTREAMS = Collections.unmodifiableMap(map);
    }

    public MineLinkConfig {
        upstreams = Collections.unmodifiableMap(new LinkedHashMap<>(upstreams));
        order = List.copyOf(order);
        directBridge = directBridge != null ? directBridge : DirectBridgeConfig.disabled();
    }

    public MineLinkConfig(
        Map<String, Upstream> upstreams,
        List<String> order,
        int upstreamTimeoutMs,
        int staggerMs,
        int deadlineMs
    ) {
        this(upstreams, order, upstreamTimeoutMs, staggerMs, deadlineMs, DirectBridgeConfig.disabled());
    }

    public static MineLinkConfig defaults() {
        return new MineLinkConfig(BUILTIN_UPSTREAMS, DEFAULT_ORDER, 3500, 250, 4500, DirectBridgeConfig.disabled());
    }

    public List<Upstream> orderedUpstreams() {
        List<Upstream> list = new ArrayList<>(order.size());
        for (String id : order) {
            Upstream u = upstreams.get(id);
            if (u != null) {
                list.add(u);
            }
        }
        return List.copyOf(list);
    }

    public static MineLinkConfig loadOrCreate(Path dataDirectory) throws IOException {
        return loadOrCreate(dataDirectory, warning -> {});
    }

    public static MineLinkConfig loadOrCreate(Path dataDirectory, Consumer<String> onWarning) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configFile = dataDirectory.resolve("config.toml");
        if (Files.notExists(configFile)) {
            try (InputStream in = MineLinkConfig.class.getResourceAsStream("/config.toml")) {
                if (in == null) {
                    throw new IOException("Bundled default config.toml resource not found");
                }
                Files.copy(in, configFile);
            }
        }
        return parse(Files.readString(configFile, StandardCharsets.UTF_8), onWarning);
    }

    public static MineLinkConfig parse(String tomlContent) {
        return parse(tomlContent, warning -> {});
    }

    public static MineLinkConfig parse(String tomlContent, Consumer<String> onWarning) {
        if (tomlContent == null) {
            throw new IllegalArgumentException("config.toml content is null");
        }
        Consumer<String> warn = onWarning != null ? onWarning : w -> {};
        String normalized = !tomlContent.isEmpty() && tomlContent.charAt(0) == '\uFEFF'
            ? tomlContent.substring(1)
            : tomlContent;

        Toml toml;
        try {
            toml = new Toml().read(normalized);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("Invalid config.toml syntax: " + e.getMessage(), e);
        }

        for (Map.Entry<String, Object> entry : toml.entrySet()) {
            String rawKey = entry.getKey();
            String unquoted = stripQuotes(rawKey);
            if (!rawKey.equals(unquoted) && KNOWN_KEYS.contains(unquoted)) {
                throw new IllegalArgumentException(
                    "config.toml: do not quote top-level key " + rawKey + " (use " + unquoted + ")"
                );
            }
            if (!KNOWN_KEYS.contains(rawKey)) {
                warn.accept("Unknown config.toml key '" + rawKey + "' (ignored)");
            }
        }

        Map<String, Upstream> upstreams = new LinkedHashMap<>(BUILTIN_UPSTREAMS);
        Toml customTable;
        try {
            customTable = toml.getTable("upstreams");
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("upstreams must be a TOML table ([upstreams])", e);
        }
        if (customTable != null) {
            Set<String> seenCustom = new LinkedHashSet<>();
            for (Map.Entry<String, Object> entry : customTable.entrySet()) {
                String id = stripQuotes(entry.getKey());
                if (!UPSTREAM_ID.matcher(id).matches()) {
                    throw new IllegalArgumentException(
                        "upstreams: invalid upstream id '" + id + "' (use lowercase a-z, 0-9, '-', max 32 chars)"
                    );
                }
                if (!seenCustom.add(id)) {
                    throw new IllegalArgumentException("upstreams: duplicate upstream id '" + id + "'");
                }
                if (!(entry.getValue() instanceof String url)) {
                    throw new IllegalArgumentException("upstreams." + id + " must be a string URL");
                }
                validateUpstreamUrl(id, url);
                upstreams.put(id, new Upstream(id, url));
            }
        }

        List<Object> rawOrder;
        try {
            rawOrder = toml.getList("order");
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("order must be a list of upstream ID strings", e);
        }
        List<String> order = new ArrayList<>();
        if (rawOrder == null) {
            order.addAll(DEFAULT_ORDER);
        } else {
            Set<String> seen = new LinkedHashSet<>();
            for (Object item : rawOrder) {
                if (!(item instanceof String s)) {
                    throw new IllegalArgumentException("order must be a list of upstream ID strings");
                }
                String id = s.trim();
                if (id.isEmpty()) {
                    continue;
                }
                if (!upstreams.containsKey(id)) {
                    throw new IllegalArgumentException(
                        "order: unknown upstream '" + id + "'. Known upstreams: " + String.join(", ", upstreams.keySet())
                    );
                }
                if (seen.add(id)) {
                    order.add(id);
                }
            }
        }
        if (order.isEmpty()) {
            throw new IllegalArgumentException("order must contain at least one upstream");
        }

        int upstreamTimeoutMs = getInt(toml, "upstream-timeout-ms", 3500, 100, 20_000);
        int staggerMs = getInt(toml, "stagger-ms", 250, 0, 20_000);
        int deadlineMs = getInt(toml, "deadline-ms", 4500, 100, 20_000);

        if (deadlineMs < upstreamTimeoutMs) {
            warn.accept(
                "deadline-ms (" + deadlineMs + ") is lower than upstream-timeout-ms (" + upstreamTimeoutMs
                    + "); the overall deadline will fire before a single upstream times out"
            );
        }

        DirectBridgeConfig directBridge = parseDirectBridge(toml, warn);
        if (directBridge.enabled() && !order.contains("mojang")) {
            warn.accept(
                "direct-bridge is enabled, but 'mojang' is not in order; direct players will fail authentication unless another configured upstream verifies them"
            );
        }

        return new MineLinkConfig(upstreams, order, upstreamTimeoutMs, staggerMs, deadlineMs, directBridge);
    }

    private static DirectBridgeConfig parseDirectBridge(Toml toml, Consumer<String> warn) {
        Toml bridgeTable;
        try {
            bridgeTable = toml.getTable("direct-bridge");
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("direct-bridge must be a TOML table ([direct-bridge])", e);
        }
        if (bridgeTable == null) {
            return DirectBridgeConfig.disabled();
        }

        for (Map.Entry<String, Object> entry : bridgeTable.entrySet()) {
            String rawKey = entry.getKey();
            String unquoted = stripQuotes(rawKey);
            if (!rawKey.equals(unquoted) && KNOWN_BRIDGE_KEYS.contains(unquoted)) {
                throw new IllegalArgumentException(
                    "direct-bridge: do not quote key " + rawKey + " (use " + unquoted + ")"
                );
            }
            if (!KNOWN_BRIDGE_KEYS.contains(rawKey)) {
                warn.accept("Unknown direct-bridge key '" + rawKey + "' (ignored)");
            }
        }

        boolean enabled = false;
        if (bridgeTable.contains("enabled")) {
            Boolean rawEnabled;
            try {
                rawEnabled = bridgeTable.getBoolean("enabled");
            } catch (ClassCastException e) {
                throw new IllegalArgumentException("direct-bridge.enabled must be a boolean", e);
            }
            if (rawEnabled == null) {
                throw new IllegalArgumentException("direct-bridge.enabled must be a boolean");
            }
            enabled = rawEnabled;
        }

        HostPort bind = new HostPort(DirectBridgeConfig.DEFAULT_BIND_HOST, DirectBridgeConfig.DEFAULT_BIND_PORT);
        if (bridgeTable.contains("bind")) {
            String rawBind = getString(bridgeTable, "bind", "direct-bridge.bind");
            bind = parseHostPort(rawBind, "direct-bridge.bind");
        }

        String targetHost = null;
        int targetPort = 0;
        if (bridgeTable.contains("target")) {
            String rawTarget = getString(bridgeTable, "target", "direct-bridge.target").trim();
            if (!rawTarget.isEmpty()) {
                HostPort target = parseHostPort(rawTarget, "direct-bridge.target");
                targetHost = target.host();
                targetPort = target.port();
            }
        }

        return new DirectBridgeConfig(enabled, bind.host(), bind.port(), targetHost, targetPort);
    }

    private static String getString(Toml table, String key, String displayName) {
        try {
            String val = table.getString(key);
            if (val == null) {
                throw new IllegalArgumentException(displayName + " must be a string");
            }
            return val;
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(displayName + " must be a string", e);
        }
    }

    private record HostPort(String host, int port) {}

    private static HostPort parseHostPort(String raw, String fieldName) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be host:port");
        }

        String host;
        String portStr;
        if (trimmed.startsWith("[")) {
            int closeBracket = trimmed.indexOf(']');
            if (closeBracket <= 1 || closeBracket + 2 > trimmed.length() || trimmed.charAt(closeBracket + 1) != ':') {
                throw new IllegalArgumentException(fieldName + " must be [ipv6]:port, got '" + raw + "'");
            }
            host = trimmed.substring(1, closeBracket).trim();
            portStr = trimmed.substring(closeBracket + 2).trim();
        } else {
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || trimmed.indexOf(':') != colon) {
                throw new IllegalArgumentException(fieldName + " must be host:port (wrap IPv6 in brackets), got '" + raw + "'");
            }
            host = trimmed.substring(0, colon).trim();
            portStr = trimmed.substring(colon + 1).trim();
        }

        if (host.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " host must not be empty");
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " has an invalid port: '" + portStr + "'", e);
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(fieldName + " port must be between 1 and 65535, got " + port);
        }
        return new HostPort(host, port);
    }

    private static String stripQuotes(String key) {
        // toml4j preserves surrounding quotes on quoted keys in entrySet().
        if (key.length() >= 2
            && ((key.startsWith("\"") && key.endsWith("\""))
                || (key.startsWith("'") && key.endsWith("'")))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static void validateUpstreamUrl(String id, String url) {
        if (url.contains("?") || url.contains("#")) {
            throw new IllegalArgumentException(
                "upstreams." + id + " must be an https:// hasJoined URL without a query string or fragment"
            );
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("upstreams." + id + " is not a valid URL: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalArgumentException(
                "upstreams." + id + " must be an absolute https:// hasJoined URL"
            );
        }
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        boolean isLoopbackHttp = "http".equalsIgnoreCase(scheme) && isLoopbackHost(host);
        if (!isHttps && !isLoopbackHttp) {
            throw new IllegalArgumentException(
                "upstreams." + id + " must use https:// (plain http:// is only allowed for loopback hosts)"
            );
        }
        int port = uri.getPort();
        if (port == 0 || port > 65_535) {
            throw new IllegalArgumentException(
                "upstreams." + id + " has an invalid port: " + port
            );
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("upstreams." + id + " must not contain credentials");
        }
    }

    private static boolean isLoopbackHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower)
            || "127.0.0.1".equals(lower)
            || "[::1]".equals(lower)
            || "::1".equals(lower);
    }

    private static int getInt(Toml toml, String key, int def, int min, int max) {
        if (!toml.contains(key)) {
            return def;
        }
        Long raw;
        try {
            raw = toml.getLong(key);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max, e);
        }
        if (raw == null || raw < min || raw > max) {
            throw new IllegalArgumentException(
                key + " must be an integer between " + min + " and " + max + ", got " + raw
            );
        }
        return raw.intValue();
    }
}
