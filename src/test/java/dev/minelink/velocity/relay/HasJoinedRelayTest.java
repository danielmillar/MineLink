package dev.minelink.velocity.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minelink.velocity.config.MineLinkConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HasJoinedRelayTest {

    @FunctionalInterface
    interface MockHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private HttpServer mockUpstreamServer;
    private HasJoinedRelay relay;
    private String baseUrl;
    private Map<String, MockHandler> handlers;
    private Map<String, List<String>> hits;
    private byte[] profileBytes;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/notch-profile-signed.json")) {
            String notch = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            String withExtra = notch.substring(0, notch.length() - 1) + ",\n  \"futureField\" : {\"x\": 1}\n}";
            this.profileBytes = withExtra.getBytes(StandardCharsets.UTF_8);
        }

        handlers = new ConcurrentHashMap<>();
        hits = new ConcurrentHashMap<>();

        mockUpstreamServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        mockUpstreamServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-upstream");
            t.setDaemon(true);
            return t;
        }));
        mockUpstreamServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String[] parts = path.split("/");
            String id = parts.length > 1 ? parts[1] : "";
            hits.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>())
                .add(exchange.getRequestURI().getRawQuery());
            MockHandler h = handlers.get(id);
            if (h != null) {
                h.handle(exchange);
            } else {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
        });
        mockUpstreamServer.start();
        baseUrl = "http://127.0.0.1:" + mockUpstreamServer.getAddress().getPort();
        relay = new HasJoinedRelay();
    }

    @AfterEach
    void tearDown() {
        relay.close();
        mockUpstreamServer.stop(0);
    }

    private Upstream upstream(String id) {
        return new Upstream(id, baseUrl + "/" + id + "/session/minecraft/hasJoined");
    }

    private MineLinkConfig config(int timeoutMs, int staggerMs, int deadlineMs) {
        return new MineLinkConfig(
            Map.of("minehut", upstream("minehut"), "minekeep", upstream("minekeep")),
            List.of("minehut", "minekeep"),
            timeoutMs,
            staggerMs,
            deadlineMs
        );
    }

    private static void sendBytes(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void returnsFirstMatchByteForByteWithoutQueryingSecond() throws Exception {
        byte[] bomBytes = new byte[profileBytes.length + 3];
        bomBytes[0] = (byte) 0xEF;
        bomBytes[1] = (byte) 0xBB;
        bomBytes[2] = (byte) 0xBF;
        System.arraycopy(profileBytes, 0, bomBytes, 3, profileBytes.length);

        handlers.put("minehut", ex -> sendBytes(ex, 200, bomBytes));

        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=-5f1c0a1b2c",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);

        HasJoinedRelay.Result.Match match = assertInstanceOf(HasJoinedRelay.Result.Match.class, result);
        assertEquals("minehut", match.upstream());
        assertArrayEquals(bomBytes, match.body());
        assertEquals(1, hits.get("minehut").size());
        assertEquals(null, hits.get("minekeep"));
    }

    @Test
    void fallsThroughToSecondUpstreamOn204AndMalformed200() throws Exception {
        handlers.put("minehut", ex -> sendBytes(ex, 200, "<html>challenge</html>".getBytes(StandardCharsets.UTF_8)));
        handlers.put("minekeep", ex -> sendBytes(ex, 200, profileBytes));

        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);

        HasJoinedRelay.Result.Match match = assertInstanceOf(HasJoinedRelay.Result.Match.class, result);
        assertEquals("minekeep", match.upstream());
        assertArrayEquals(profileBytes, match.body());
    }

    @Test
    void returnsNoMatchOnlyWhenAllUpstreamsReturn204() throws Exception {
        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);

        assertInstanceOf(HasJoinedRelay.Result.NoMatch.class, result);
        assertEquals(1, hits.get("minehut").size());
        assertEquals(1, hits.get("minekeep").size());
    }

    @Test
    void rejectsUpstreamRedirectAsError() throws Exception {
        handlers.put("minehut", ex -> {
            ex.getResponseHeaders().set("Location", baseUrl + "/minekeep/session/minecraft/hasJoined");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut")),
            "username=Notch&serverId=abc",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);

        HasJoinedRelay.Result.Error err = assertInstanceOf(HasJoinedRelay.Result.Error.class, result);
        assertTrue(err.summary().contains("status 302"));
    }

    @Test
    void slowFirstUpstreamStartsSecondAfterStagger() throws Exception {
        handlers.put("minehut", ex -> {
            sleepQuietly(1500);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        handlers.put("minekeep", ex -> sendBytes(ex, 200, profileBytes));

        long t0 = System.currentTimeMillis();
        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(2000, 80, 3000)
        ).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        HasJoinedRelay.Result.Match match = assertInstanceOf(HasJoinedRelay.Result.Match.class, result);
        assertEquals("minekeep", match.upstream());
        assertTrue(elapsed >= 60 && elapsed < 1000, "elapsed=" + elapsed);
    }

    @Test
    void slowFirstUpstreamIsNotCancelledByStaggerAndLateMatchStillWins() throws Exception {
        handlers.put("minehut", ex -> {
            sleepQuietly(180);
            sendBytes(ex, 200, profileBytes);
        });

        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(2000, 50, 3000)
        ).get(5, TimeUnit.SECONDS);

        HasJoinedRelay.Result.Match match = assertInstanceOf(HasJoinedRelay.Result.Match.class, result);
        assertEquals("minehut", match.upstream());
        assertEquals(1, hits.get("minekeep").size());
    }

    @Test
    void staggerZeroQueriesAllUpstreamsInParallel() throws Exception {
        handlers.put("minehut", ex -> {
            sleepQuietly(120);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        handlers.put("minekeep", ex -> {
            sleepQuietly(120);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });

        long t0 = System.currentTimeMillis();
        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(1000, 0, 2000)
        ).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        assertInstanceOf(HasJoinedRelay.Result.NoMatch.class, result);
        assertTrue(elapsed < 220, "Expected parallel execution, elapsed=" + elapsed);
    }

    @Test
    void hardDeadlineBoundsHangingUpstreams() throws Exception {
        handlers.put("minehut", ex -> {
            sleepQuietly(2000);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });

        long t0 = System.currentTimeMillis();
        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(5000, 5000, 180)
        ).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        HasJoinedRelay.Result.Error err = assertInstanceOf(HasJoinedRelay.Result.Error.class, result);
        assertTrue(elapsed >= 150 && elapsed < 1000, "elapsed=" + elapsed);
        assertEquals(null, hits.get("minekeep"));
        assertTrue(err.summary().contains("deadline"));
        assertTrue(err.summary().contains("not reached"));
    }
}
