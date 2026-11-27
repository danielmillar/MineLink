package dev.minelink.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DirectProxyBridgeTest {

    private static final byte[] PROXY_V2_SIG = {
        0x0D, 0x0A, 0x0D, 0x0A,
        0x00, 0x0D, 0x0A, 0x51,
        0x55, 0x49, 0x54, 0x0A
    };

    @Test
    void prependsProxyV2HeaderAndForwardsLargeBidirectionalPayload() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        AtomicReference<byte[]> receivedHeader = new AtomicReference<>();

        try (ServerSocket backend = new ServerSocket(0, 16, loopback);
             DirectProxyBridge bridge = new DirectProxyBridge(
                 new InetSocketAddress(loopback, 0),
                 new InetSocketAddress(loopback, backend.getLocalPort()),
                 LoggerFactory.getLogger(DirectProxyBridgeTest.class)
             )) {
            bridge.start();

            Thread backendThread = new Thread(() -> {
                try (Socket s = backend.accept()) {
                    s.setSoTimeout(5_000);
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    byte[] header = new byte[28];
                    in.readFully(header);
                    receivedHeader.set(header);

                    byte[] payload = in.readAllBytes();
                    OutputStream out = s.getOutputStream();
                    out.write(payload);
                    out.flush();
                    s.shutdownOutput();
                } catch (IOException ignored) {
                }
            }, "test-backend");
            backendThread.setDaemon(true);
            backendThread.start();

            byte[] largePayload = new byte[256 * 1024];
            new Random(42).nextBytes(largePayload);

            int clientLocalPort;
            byte[] echoed;
            try (Socket client = new Socket(loopback, bridge.port())) {
                client.setSoTimeout(5_000);
                clientLocalPort = client.getLocalPort();
                OutputStream out = client.getOutputStream();
                out.write(largePayload);
                out.flush();
                client.shutdownOutput();

                echoed = client.getInputStream().readAllBytes();
            }

            backendThread.join(5_000);
            byte[] header = receivedHeader.get();
            assertEquals(28, header.length);
            byte[] sig = new byte[12];
            System.arraycopy(header, 0, sig, 0, 12);
            assertArrayEquals(PROXY_V2_SIG, sig);
            assertEquals(0x21, header[12] & 0xFF);
            assertEquals(0x11, header[13] & 0xFF);
            assertEquals(12, ((header[14] & 0xFF) << 8) | (header[15] & 0xFF));
            assertArrayEquals(new byte[] {127, 0, 0, 1}, new byte[] {header[16], header[17], header[18], header[19]});
            assertArrayEquals(new byte[] {127, 0, 0, 1}, new byte[] {header[20], header[21], header[22], header[23]});
            assertEquals(clientLocalPort, ((header[24] & 0xFF) << 8) | (header[25] & 0xFF));
            assertEquals(bridge.port(), ((header[26] & 0xFF) << 8) | (header[27] & 0xFF));

            assertArrayEquals(largePayload, echoed);
            waitForZeroConnections(bridge);
        }
    }

    @Test
    void handlesConcurrentClientsIndependently() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket backend = new ServerSocket(0, 32, loopback);
             DirectProxyBridge bridge = new DirectProxyBridge(
                 new InetSocketAddress(loopback, 0),
                 new InetSocketAddress(loopback, backend.getLocalPort()),
                 LoggerFactory.getLogger(DirectProxyBridgeTest.class)
             )) {
            bridge.start();

            Thread acceptLoop = new Thread(() -> {
                while (!backend.isClosed()) {
                    try {
                        Socket s = backend.accept();
                        pool.submit(() -> {
                            try (s) {
                                s.setSoTimeout(5_000);
                                DataInputStream in = new DataInputStream(s.getInputStream());
                                byte[] header = new byte[28];
                                in.readFully(header);
                                int srcPort = ((header[24] & 0xFF) << 8) | (header[25] & 0xFF);
                                byte[] msg = in.readAllBytes();
                                String reply = srcPort + ":" + new String(msg, StandardCharsets.UTF_8);
                                OutputStream out = s.getOutputStream();
                                out.write(reply.getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                s.shutdownOutput();
                            } catch (IOException ignored) {
                            }
                        });
                    } catch (IOException ignored) {
                        break;
                    }
                }
            }, "test-backend-accept");
            acceptLoop.setDaemon(true);
            acceptLoop.start();

            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final int id = i;
                tasks.add(() -> {
                    try (Socket client = new Socket(loopback, bridge.port())) {
                        client.setSoTimeout(5_000);
                        int port = client.getLocalPort();
                        String payload = "client-" + id;
                        client.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
                        client.getOutputStream().flush();
                        client.shutdownOutput();
                        String reply = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        assertEquals(port + ":" + payload, reply);
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }

            waitForZeroConnections(bridge);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closesClientCleanlyWhenBackendRefusesConnection() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        int unusedPort;
        try (ServerSocket tmp = new ServerSocket(0, 1, loopback)) {
            unusedPort = tmp.getLocalPort();
        }

        try (DirectProxyBridge bridge = new DirectProxyBridge(
            new InetSocketAddress(loopback, 0),
            new InetSocketAddress(loopback, unusedPort),
            LoggerFactory.getLogger(DirectProxyBridgeTest.class)
        )) {
            bridge.start();

            try (Socket client = new Socket(loopback, bridge.port())) {
                client.setSoTimeout(3_000);
                InputStream in = client.getInputStream();
                assertEquals(-1, in.read());
            }

            waitForZeroConnections(bridge);
        }
    }

    private static void waitForZeroConnections(DirectProxyBridge bridge) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (bridge.activeConnections() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, bridge.activeConnections());
    }
}
