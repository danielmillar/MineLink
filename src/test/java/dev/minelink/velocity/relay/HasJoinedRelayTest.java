package dev.minelink.velocity.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minelink.velocity.BuildConstants;
import dev.minelink.velocity.config.MineLinkConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HasJoinedRelayTest {

    @FunctionalInterface
    interface MockHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private HttpServer mockUpstreamServer;
    private ExecutorService mockExecutor;
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

        mockExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-upstream");
            t.setDaemon(true);
            return t;
        });
        mockUpstreamServer = HttpServer.create(
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0),
            0
        );
        mockUpstreamServer.setExecutor(mockExecutor);
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
        mockExecutor.shutdownNow();
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
    void returnsFirstMatchByteForByteWithExpectedUserAgentWithoutQueryingSecond() throws Exception {
        byte[] bomBytes = new byte[profileBytes.length + 3];
        bomBytes[0] = (byte) 0xEF;
        bomBytes[1] = (byte) 0xBB;
        bomBytes[2] = (byte) 0xBF;
        System.arraycopy(profileBytes, 0, bomBytes, 3, profileBytes.length);

        AtomicReference<String> seenUa = new AtomicReference<>();
        handlers.put("minehut", ex -> {
            seenUa.set(ex.getRequestHeaders().getFirst("User-Agent"));
            sendBytes(ex, 200, bomBytes);
        });

        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=-5f1c0a1b2c",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);

        HasJoinedRelay.Result.Match match = assertInstanceOf(HasJoinedRelay.Result.Match.class, result);
        assertEquals("minehut", match.upstream());
        assertEquals("minelink/" + BuildConstants.VERSION, seenUa.get());
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
    void returnsNoMatchOnlyWhenAllUpstreamsReturn204AndErrorOnMixedFailure() throws Exception {
        HasJoinedRelay.Result noMatch = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);

        assertInstanceOf(HasJoinedRelay.Result.NoMatch.class, noMatch);
        assertEquals(1, hits.get("minehut").size());
        assertEquals(1, hits.get("minekeep").size());

        handlers.put("minehut", ex -> {
            byte[] hugeError = new byte[256 * 1024];
            Arrays.fill(hugeError, (byte) 'x');
            ex.sendResponseHeaders(502, hugeError.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(hugeError);
            }
        });
        HasJoinedRelay.Result mixed = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);

        HasJoinedRelay.Result.Error err = assertInstanceOf(HasJoinedRelay.Result.Error.class, mixed);
        assertTrue(err.summary().contains("status 502"));
    }

    @Test
    void rejectsOversized200BodiesBothWithContentLengthAndChunked() throws Exception {
        byte[] huge = new byte[HasJoinedRelay.MAX_PROFILE_BYTES + 1024];
        Arrays.fill(huge, (byte) 'a');

        handlers.put("minehut", ex -> sendBytes(ex, 200, huge));
        HasJoinedRelay.Result knownLen = relay.fanOut(
            List.of(upstream("minehut")),
            "username=Notch&serverId=abc",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);
        HasJoinedRelay.Result.Error err1 = assertInstanceOf(HasJoinedRelay.Result.Error.class, knownLen);
        assertTrue(err1.summary().contains("exceeds"));

        handlers.put("minehut", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(huge);
            } catch (IOException ignored) {
            }
        });
        HasJoinedRelay.Result chunked = relay.fanOut(
            List.of(upstream("minehut")),
            "username=Notch&serverId=abc",
            config(1000, 500, 2000)
        ).get(5, TimeUnit.SECONDS);
        HasJoinedRelay.Result.Error err2 = assertInstanceOf(HasJoinedRelay.Result.Error.class, chunked);
        assertTrue(err2.summary().contains("exceeds"));
    }

    @Test
    void perUpstreamTimeoutEnforcesFullBodyCompletionBeforeDeadline() throws Exception {
        handlers.put("minehut", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write("{\"id\":\"069a".getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleepQuietly(1500);
            } catch (IOException ignored) {
            }
        });

        long t0 = System.currentTimeMillis();
        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut")),
            "username=Notch&serverId=abc",
            config(200, 1000, 3000)
        ).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        HasJoinedRelay.Result.Error err = assertInstanceOf(HasJoinedRelay.Result.Error.class, result);
        assertTrue(err.summary().contains("minehut: timeout"), err.summary());
        assertTrue(elapsed < 1000, "Expected per-upstream timeout before deadline, elapsed=" + elapsed);
    }

    @Test
    void handlesConnectionRefusedCleanly() throws Exception {
        int unusedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            unusedPort = s.getLocalPort();
        }
        Upstream dead = new Upstream("dead", "http://127.0.0.1:" + unusedPort + "/hasJoined");

        HasJoinedRelay.Result result = relay.fanOut(
            List.of(dead),
            "username=Notch&serverId=abc",
            config(1000, 250, 2000)
        ).get(5, TimeUnit.SECONDS);

        assertInstanceOf(HasJoinedRelay.Result.Error.class, result);
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
    void non200WithOpenBodyFailsImmediatelyAtHeadersAndFallsBack() throws Exception {
        handlers.put("minehut", ex -> {
            ex.sendResponseHeaders(502, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write("bad gateway".getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleepQuietly(1500);
            } catch (IOException ignored) {
            }
        });
        handlers.put("minekeep", ex -> sendBytes(ex, 200, profileBytes));

        long t0 = System.currentTimeMillis();
        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(2000, 1500, 3000)
        ).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        HasJoinedRelay.Result.Match match = assertInstanceOf(HasJoinedRelay.Result.Match.class, result);
        assertEquals("minekeep", match.upstream());
        assertTrue(elapsed < 800, "Expected immediate fallback on 502 headers, elapsed=" + elapsed);
    }

    @Test
    void slowFirstUpstreamIsNotCancelledByStaggerAndLateMatchStillWins() throws Exception {
        CountDownLatch secondArrived = new CountDownLatch(1);
        handlers.put("minehut", ex -> {
            try {
                secondArrived.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sendBytes(ex, 200, profileBytes);
        });
        handlers.put("minekeep", ex -> {
            secondArrived.countDown();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });

        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(2000, 50, 3000)
        ).get(5, TimeUnit.SECONDS);

        HasJoinedRelay.Result.Match match = assertInstanceOf(HasJoinedRelay.Result.Match.class, result);
        assertEquals("minehut", match.upstream());
        assertEquals(0, secondArrived.getCount());
        assertEquals(1, hits.get("minekeep").size());
    }

    @Test
    void staggerZeroQueriesAllUpstreamsInParallel() throws Exception {
        CountDownLatch bothArrived = new CountDownLatch(2);
        AtomicInteger reachedTogether = new AtomicInteger(0);
        MockHandler barrierHandler = ex -> {
            bothArrived.countDown();
            try {
                if (bothArrived.await(2, TimeUnit.SECONDS)) {
                    reachedTogether.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        };
        handlers.put("minehut", barrierHandler);
        handlers.put("minekeep", barrierHandler);

        HasJoinedRelay.Result result = relay.fanOut(
            List.of(upstream("minehut"), upstream("minekeep")),
            "username=Notch&serverId=abc",
            config(2000, 0, 3000)
        ).get(5, TimeUnit.SECONDS);

        assertEquals(2, reachedTogether.get(), "Expected both upstreams to reach the barrier concurrently");
        assertInstanceOf(HasJoinedRelay.Result.NoMatch.class, result);
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
