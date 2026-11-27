package dev.minelink.velocity.bridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

public final class DirectProxyBridge implements AutoCloseable {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final long SELECT_TIMEOUT_WHEN_CONNECTING_MS = 250;

    private final InetSocketAddress boundAddress;
    private final InetSocketAddress targetAddress;
    private final long connectTimeoutNanos;
    private final Logger logger;
    private final ServerSocketChannel serverChannel;
    private final Selector selector;
    private final Set<Tunnel> tunnels = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread worker;

    public DirectProxyBridge(
        InetSocketAddress bindAddress,
        InetSocketAddress targetAddress,
        Logger logger
    ) throws IOException {
        this(bindAddress, targetAddress, DEFAULT_CONNECT_TIMEOUT_MS, logger);
    }

    DirectProxyBridge(
        InetSocketAddress bindAddress,
        InetSocketAddress targetAddress,
        long connectTimeoutMs,
        Logger logger
    ) throws IOException {
        Objects.requireNonNull(bindAddress, "bindAddress");
        this.targetAddress = Objects.requireNonNull(targetAddress, "targetAddress");
        this.connectTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(100, connectTimeoutMs));
        this.logger = Objects.requireNonNull(logger, "logger");

        ServerSocketChannel server = null;
        Selector sel = null;
        try {
            server = ServerSocketChannel.open();
            server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            server.bind(bindAddress);
            server.configureBlocking(false);

            sel = Selector.open();
            server.register(sel, SelectionKey.OP_ACCEPT);

            this.serverChannel = server;
            this.selector = sel;
            this.boundAddress = (InetSocketAddress) server.getLocalAddress();
        } catch (IOException | RuntimeException e) {
            closeQuietly(sel);
            closeQuietly(server);
            throw e;
        }

        this.worker = new Thread(this::eventLoop, "minelink-direct-bridge");
        this.worker.setDaemon(true);
    }

    public void start() {
        if (!closed.get()) {
            worker.start();
        }
    }

    public InetSocketAddress boundAddress() {
        return boundAddress;
    }

    public InetSocketAddress targetAddress() {
        return targetAddress;
    }

    public int port() {
        return boundAddress.getPort();
    }

    public int activeConnections() {
        return tunnels.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        selector.wakeup();
        if (worker.isAlive() && Thread.currentThread() != worker) {
            try {
                worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        cleanupAll();
    }

    private void eventLoop() {
        try {
            while (!closed.get()) {
                boolean hasConnecting = hasConnectingTunnels();
                if (hasConnecting) {
                    selector.select(SELECT_TIMEOUT_WHEN_CONNECTING_MS);
                } else {
                    selector.select();
                }

                if (closed.get()) {
                    break;
                }

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isAcceptable()) {
                            acceptClient();
                        } else {
                            Tunnel tunnel = (Tunnel) key.attachment();
                            if (tunnel != null) {
                                tunnel.handle(key);
                            }
                        }
                    } catch (CancelledKeyException ignored) {
                    } catch (Exception e) {
                        Object att = key.attachment();
                        if (att instanceof Tunnel tunnel) {
                            tunnel.close();
                        } else {
                            logger.warn("Direct bridge error: {}", e.getMessage());
                        }
                    }
                }

                if (hasConnecting) {
                    expireStalledConnects();
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                logger.error("Direct bridge event loop stopped unexpectedly", e);
            }
        } finally {
            cleanupAll();
        }
    }

    private void acceptClient() {
        while (!closed.get()) {
            SocketChannel client;
            try {
                client = serverChannel.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    logger.warn("Direct bridge accept failed: {}", e.getMessage());
                }
                return;
            }
            if (client == null) {
                return;
            }
            openTunnel(client);
        }
    }

    private void openTunnel(SocketChannel client) {
        SocketChannel backend = null;
        try {
            client.configureBlocking(false);
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);

            InetSocketAddress remote = (InetSocketAddress) client.getRemoteAddress();
            InetSocketAddress local = (InetSocketAddress) client.getLocalAddress();
            byte[] proxyHeader = ProxyProtocolV2.encode(remote, local);

            backend = SocketChannel.open();
            backend.configureBlocking(false);
            backend.setOption(StandardSocketOptions.TCP_NODELAY, true);

            boolean connected = backend.connect(targetAddress);
            Tunnel tunnel = new Tunnel(client, backend, proxyHeader, !connected, System.nanoTime() + connectTimeoutNanos);
            tunnels.add(tunnel);
            tunnel.register();
        } catch (IOException | RuntimeException e) {
            logger.debug("Direct bridge failed to open tunnel to {}: {}", targetAddress, e.getMessage());
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private boolean hasConnectingTunnels() {
        for (Tunnel tunnel : tunnels) {
            if (tunnel.connecting) {
                return true;
            }
        }
        return false;
    }

    private void expireStalledConnects() {
        long now = System.nanoTime();
        for (Tunnel tunnel : tunnels) {
            if (tunnel.connecting && now - tunnel.connectDeadlineNanos >= 0) {
                tunnel.close();
            }
        }
    }

    private void cleanupAll() {
        for (Tunnel tunnel : tunnels) {
            tunnel.close();
        }
        closeQuietly(serverChannel);
        closeQuietly(selector);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private final class Tunnel {
        final SocketChannel client;
        final SocketChannel backend;
        final ByteBuffer toBackend;
        final ByteBuffer toClient;
        final long connectDeadlineNanos;
        SelectionKey clientKey;
        SelectionKey backendKey;
        boolean connecting;
        boolean clientEof;
        boolean backendEof;
        boolean clientOutputShutdown;
        boolean backendOutputShutdown;
        boolean closed;

        Tunnel(
            SocketChannel client,
            SocketChannel backend,
            byte[] proxyHeader,
            boolean connecting,
            long connectDeadlineNanos
        ) {
            this.client = client;
            this.backend = backend;
            this.connecting = connecting;
            this.connectDeadlineNanos = connectDeadlineNanos;

            this.toBackend = ByteBuffer.allocate(BUFFER_SIZE);
            this.toBackend.put(proxyHeader);
            this.toBackend.flip();

            this.toClient = ByteBuffer.allocate(BUFFER_SIZE);
            this.toClient.flip();
        }

        void register() throws IOException {
            if (connecting) {
                this.clientKey = client.register(selector, 0, this);
                this.backendKey = backend.register(selector, SelectionKey.OP_CONNECT, this);
            } else {
                this.clientKey = client.register(selector, SelectionKey.OP_READ, this);
                this.backendKey = backend.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
            }
        }

        void handle(SelectionKey key) {
            if (closed) {
                return;
            }
            try {
                if (key == backendKey && key.isConnectable()) {
                    if (!backend.finishConnect()) {
                        return;
                    }
                    connecting = false;
                    flush(backend, toBackend);
                    updateInterests();
                    return;
                }

                if (key == clientKey) {
                    if (key.isReadable()) {
                        if (readInto(client, toBackend)) {
                            clientEof = true;
                        }
                        if (!connecting) {
                            flush(backend, toBackend);
                        }
                    }
                    if (!closed && key.isValid() && key.isWritable()) {
                        flush(client, toClient);
                    }
                } else if (key == backendKey) {
                    if (key.isReadable()) {
                        if (readInto(backend, toClient)) {
                            backendEof = true;
                        }
                        flush(client, toClient);
                    }
                    if (!closed && key.isValid() && key.isWritable()) {
                        flush(backend, toBackend);
                    }
                }

                if (!closed) {
                    updateInterests();
                }
            } catch (IOException e) {
                close();
            }
        }

        private boolean readInto(SocketChannel src, ByteBuffer buf) throws IOException {
            buf.compact();
            int read;
            try {
                read = src.read(buf);
            } finally {
                buf.flip();
            }
            return read < 0;
        }

        private void flush(SocketChannel dst, ByteBuffer buf) throws IOException {
            while (buf.hasRemaining()) {
                int written = dst.write(buf);
                if (written <= 0) {
                    break;
                }
            }
        }

        private void updateInterests() throws IOException {
            if (closed || connecting) {
                return;
            }

            if (clientEof && !toBackend.hasRemaining() && !backendOutputShutdown) {
                backend.shutdownOutput();
                backendOutputShutdown = true;
            }
            if (backendEof && !toClient.hasRemaining() && !clientOutputShutdown) {
                client.shutdownOutput();
                clientOutputShutdown = true;
            }
            if (clientOutputShutdown && backendOutputShutdown) {
                close();
                return;
            }

            int clientOps = 0;
            if (!clientEof && toBackend.remaining() < toBackend.capacity()) {
                clientOps |= SelectionKey.OP_READ;
            }
            if (toClient.hasRemaining()) {
                clientOps |= SelectionKey.OP_WRITE;
            }

            int backendOps = 0;
            if (!backendEof && toClient.remaining() < toClient.capacity()) {
                backendOps |= SelectionKey.OP_READ;
            }
            if (toBackend.hasRemaining()) {
                backendOps |= SelectionKey.OP_WRITE;
            }

            if (clientKey != null && clientKey.isValid()) {
                clientKey.interestOps(clientOps);
            }
            if (backendKey != null && backendKey.isValid()) {
                backendKey.interestOps(backendOps);
            }
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            tunnels.remove(this);
            if (clientKey != null) {
                clientKey.cancel();
            }
            if (backendKey != null) {
                backendKey.cancel();
            }
            closeQuietly(client);
            closeQuietly(backend);
        }
    }
}
