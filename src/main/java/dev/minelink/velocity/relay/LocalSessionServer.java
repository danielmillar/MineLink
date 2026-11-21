package dev.minelink.velocity.relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minelink.velocity.config.MineLinkConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;

public final class LocalSessionServer implements AutoCloseable {

    private static final int MAX_PARAM_LENGTH = 64;
    private static final String HAS_JOINED_SUFFIX = "/session/minecraft/hasJoined";

    private final HttpServer server;
    private final ExecutorService serverExecutor;
    private final HasJoinedRelay relay;
    private final Logger logger;
    private final Supplier<MineLinkConfig> configSupplier;
    private final String expectedPath;
    private final byte[] expectedPathBytes;
    private final int port;

    public LocalSessionServer(
        Supplier<MineLinkConfig> configSupplier,
        HasJoinedRelay relay,
        Logger logger
    ) throws IOException {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.relay = Objects.requireNonNull(relay, "relay");
        this.logger = Objects.requireNonNull(logger, "logger");

        byte[] tokenBytes = new byte[16];
        new SecureRandom().nextBytes(tokenBytes);
        this.expectedPath = "/" + HexFormat.of().formatHex(tokenBytes) + HAS_JOINED_SUFFIX;
        this.expectedPathBytes = this.expectedPath.getBytes(StandardCharsets.UTF_8);

        InetAddress ipv4Loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        this.server = HttpServer.create(new InetSocketAddress(ipv4Loopback, 0), 0);
        this.port = server.getAddress().getPort();

        AtomicInteger idx = new AtomicInteger(1);
        this.serverExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "minelink-loopback-" + idx.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        );
        this.server.setExecutor(serverExecutor);
        this.server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return port;
    }

    public String sessionServerUrl() {
        return "http://127.0.0.1:" + port + expectedPath;
    }

    @Override
    public void close() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        try {
            String rawPath = exchange.getRequestURI().getRawPath();
            if (rawPath == null || !MessageDigest.isEqual(
                rawPath.getBytes(StandardCharsets.UTF_8),
                expectedPathBytes
            )) {
                sendEmpty(exchange, 404);
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                sendEmpty(exchange, 405);
                return;
            }

            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (!hasRequiredParams(rawQuery)) {
                sendEmpty(exchange, 400);
                return;
            }

            MineLinkConfig config = configSupplier.get();
            relay.fanOut(config.orderedUpstreams(), rawQuery, config)
                .whenCompleteAsync((result, throwable) -> {
                    try {
                        if (throwable != null) {
                            logger.warn("Session verification failed: {}", throwable.getMessage());
                            sendEmpty(exchange, 503);
                        } else if (result instanceof HasJoinedRelay.Result.Match match) {
                            byte[] body = match.body();
                            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                            exchange.getResponseHeaders().set("Cache-Control", "no-store");
                            exchange.getResponseHeaders().set("X-MineLink-Upstream", match.upstream());
                            exchange.sendResponseHeaders(200, body.length);
                            try (OutputStream out = exchange.getResponseBody()) {
                                out.write(body);
                            }
                        } else if (result instanceof HasJoinedRelay.Result.NoMatch) {
                            sendEmpty(exchange, 204);
                        } else if (result instanceof HasJoinedRelay.Result.Error err) {
                            // Velocity treats 204 as "online mode only" and non-200/204 as "authentication servers are down".
                            logger.warn("Session verification failed ({})", err.summary());
                            sendEmpty(exchange, 503);
                        }
                    } catch (Exception e) {
                        logger.error("Failed writing hasJoined response to Velocity", e);
                        try {
                            exchange.close();
                        } catch (Exception ignored) {
                        }
                    }
                }, serverExecutor);
        } catch (Exception e) {
            logger.error("Failed handling local session request", e);
            try {
                sendEmpty(exchange, 503);
            } catch (IOException ignored) {
                exchange.close();
            }
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static boolean hasRequiredParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return false;
        }
        String username = null;
        String serverId = null;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawVal = eq >= 0 ? pair.substring(eq + 1) : "";
            String key;
            String val;
            try {
                key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                val = URLDecoder.decode(rawVal, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return false;
            }
            if ("username".equals(key) && username == null) {
                username = val;
            } else if ("serverId".equals(key) && serverId == null) {
                serverId = val;
            }
        }
        return username != null && !username.isEmpty() && username.length() <= MAX_PARAM_LENGTH
            && serverId != null && !serverId.isEmpty() && serverId.length() <= MAX_PARAM_LENGTH;
    }
}
