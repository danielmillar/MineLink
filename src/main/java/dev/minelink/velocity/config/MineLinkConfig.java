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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record MineLinkConfig(
    Map<String, Upstream> upstreams,
    List<String> order,
    int upstreamTimeoutMs,
    int staggerMs,
    int deadlineMs
) {

    public static final Map<String, Upstream> BUILTIN_UPSTREAMS;
    private static final Pattern UPSTREAM_ID = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final List<String> DEFAULT_ORDER = List.of("minehut", "minekeep");

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
        BUILTIN_UPSTREAMS = Collections.unmodifiableMap(map);
    }

    public MineLinkConfig {
        upstreams = Collections.unmodifiableMap(new LinkedHashMap<>(upstreams));
        order = List.copyOf(order);
    }

    public static MineLinkConfig defaults() {
        return new MineLinkConfig(BUILTIN_UPSTREAMS, DEFAULT_ORDER, 3500, 250, 4500);
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
        return parse(Files.readString(configFile, StandardCharsets.UTF_8));
    }

    public static MineLinkConfig parse(String tomlContent) {
        Toml toml = new Toml().read(tomlContent);

        Map<String, Upstream> upstreams = new LinkedHashMap<>(BUILTIN_UPSTREAMS);
        Toml customTable = toml.getTable("upstreams");
        if (customTable != null) {
            for (Map.Entry<String, Object> entry : customTable.entrySet()) {
                String id = entry.getKey();
                if (!UPSTREAM_ID.matcher(id).matches()) {
                    throw new IllegalArgumentException(
                        "upstreams: invalid upstream id '" + id + "' (use lowercase a-z, 0-9, '-', max 32 chars)"
                    );
                }
                if (!(entry.getValue() instanceof String url)) {
                    throw new IllegalArgumentException("upstreams." + id + " must be a string URL");
                }
                validateUpstreamUrl(id, url);
                upstreams.put(id, new Upstream(id, url));
            }
        }

        List<Object> rawOrder = toml.getList("order");
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

        return new MineLinkConfig(upstreams, order, upstreamTimeoutMs, staggerMs, deadlineMs);
    }

    private static void validateUpstreamUrl(String id, String url) {
        if (url.contains("?") || url.contains("#")) {
            throw new IllegalArgumentException(
                "upstreams." + id + " must be an http(s) hasJoined URL without a query string or fragment"
            );
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("upstreams." + id + " is not a valid URL: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException(
                "upstreams." + id + " must be an absolute http(s) hasJoined URL"
            );
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("upstreams." + id + " must not contain credentials");
        }
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
