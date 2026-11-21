package dev.minelink.velocity.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minelink.velocity.config.MineLinkConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LocalSessionServerTest {

    @FunctionalInterface
    interface MockHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private HttpServer mockUpstreamServer;
    private ExecutorService mockExecutor;
    private HasJoinedRelay relay;
    private AtomicReference<MineLinkConfig> configRef;
    private LocalSessionServer localServer;
    private HttpClient velocityClient;
    private Map<String, MockHandler> handlers;
    private Map<String, List<String>> hits;
    private List<String> arrivalOrder;
    private byte[] profileBytes;
    private String mockBase;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/notch-profile-signed.json")) {
            this.profileBytes = in.readAllBytes();
        }
        handlers = new ConcurrentHashMap<>();
        hits = new ConcurrentHashMap<>();
        arrivalOrder = new CopyOnWriteArrayList<>();

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
            arrivalOrder.add(id);
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
        mockBase = "http://127.0.0.1:" + mockUpstreamServer.getAddress().getPort();

        relay = new HasJoinedRelay();
        configRef = new AtomicReference<>(config(List.of("minehut", "minekeep")));
        localServer = new LocalSessionServer(
            configRef::get,
            relay,
            LoggerFactory.getLogger(LocalSessionServerTest.class)
        );
        localServer.start();
        velocityClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        localServer.close();
        relay.close();
        mockUpstreamServer.stop(0);
        mockExecutor.shutdownNow();
    }

    private MineLinkConfig config(List<String> order) {
        return new MineLinkConfig(
            Map.of(
                "minehut", new Upstream("minehut", mockBase + "/minehut/mitm/proxy/session/minecraft/hasJoined"),
                "minekeep", new Upstream("minekeep", mockBase + "/minekeep/session/minecraft/hasJoined")
            ),
            order,
            1000,
            250,
            2000
        );
    }

    private static void sendBytes(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private HttpResponse<byte[]> getHasJoined(String query) throws Exception {
        String url = query == null ? localServer.sessionServerUrl() : localServer.sessionServerUrl() + "?" + query;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("User-Agent", "Velocity/4.3.1")
            .build();
        return velocityClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void enforcesSecretPathMethodAndRequiredQueryParams() throws Exception {
        HttpResponse<byte[]> wrongPath = velocityClient.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localServer.port() + "/session/minecraft/hasJoined?username=Notch&serverId=1"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals(404, wrongPath.statusCode());

        HttpResponse<byte[]> wrongMethod = velocityClient.send(
            HttpRequest.newBuilder(URI.create(localServer.sessionServerUrl() + "?username=Notch&serverId=1"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("GET", wrongMethod.headers().firstValue("allow").orElse(""));

        assertEquals(400, getHasJoined(null).statusCode());
        assertEquals(400, getHasJoined("username=Notch").statusCode());
        assertEquals(400, getHasJoined("username=" + "a".repeat(65) + "&serverId=1").statusCode());
        assertEquals(400, getHasJoined("username=Notch&serverId=" + "b".repeat(65)).statusCode());

        String path = URI.create(localServer.sessionServerUrl()).getRawPath();
        try (Socket socket = new Socket("127.0.0.1", localServer.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + "?username=%ZZ&serverId=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertEquals(true, statusLine != null && statusLine.contains("400"), "statusLine=" + statusLine);
        }
    }

    @Test
    void forwardsQueryVerbatimAndPassesThroughProfileBytes() throws Exception {
        handlers.put("minekeep", ex -> sendBytes(ex, 200, profileBytes));

        String rawQuery = "username=Steve%5F1&serverId=-1a2b3c&ip=203.0.113.9";
        HttpResponse<byte[]> res = getHasJoined(rawQuery);

        assertEquals(200, res.statusCode());
        assertEquals("minekeep", res.headers().firstValue("x-minelink-upstream").orElse(""));
        assertEquals("no-store", res.headers().firstValue("cache-control").orElse(""));
        assertArrayEquals(profileBytes, res.body());
        assertEquals(List.of("minehut", "minekeep"), arrivalOrder);
        assertEquals(List.of(rawQuery), hits.get("minehut"));
        assertEquals(List.of(rawQuery), hits.get("minekeep"));
    }

    @Test
    void returns204OnAllNoMatch503OnErrorAndReloadsOrderLive() throws Exception {
        HttpResponse<byte[]> noMatch = getHasJoined("username=Notch&serverId=1");
        assertEquals(204, noMatch.statusCode());
        assertEquals(0, noMatch.body().length);

        handlers.put("minekeep", ex -> {
            ex.sendResponseHeaders(502, -1);
            ex.close();
        });
        HttpResponse<byte[]> errored = getHasJoined("username=Notch&serverId=2");
        assertEquals(503, errored.statusCode());
        assertEquals(0, errored.body().length);

        configRef.set(config(List.of("minehut")));
        hits.clear();
        assertEquals(204, getHasJoined("username=Notch&serverId=3").statusCode());
        assertEquals(1, hits.get("minehut").size());
        assertEquals(null, hits.get("minekeep"));
    }

    @Test
    void handlesConcurrentLogins() throws Exception {
        handlers.put("minekeep", ex -> sendBytes(ex, 200, profileBytes));

        List<CompletableFuture<HttpResponse<byte[]>>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            HttpRequest req = HttpRequest.newBuilder(
                URI.create(localServer.sessionServerUrl() + "?username=Notch&serverId=c" + i)
            ).GET().build();
            futures.add(velocityClient.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
        for (CompletableFuture<HttpResponse<byte[]>> f : futures) {
            assertEquals(200, f.join().statusCode());
        }

        assertEquals(10, hits.get("minehut").size());
        assertEquals(10, hits.get("minekeep").size());
    }
}
