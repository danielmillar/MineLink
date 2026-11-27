package dev.minelink.velocity.config;

import java.net.InetSocketAddress;
import java.util.Objects;

public record DirectBridgeConfig(
    boolean enabled,
    String bindHost,
    int bindPort,
    String targetHost,
    int targetPort
) {

    public static final String DEFAULT_BIND_HOST = "0.0.0.0";
    public static final int DEFAULT_BIND_PORT = 25566;

    public DirectBridgeConfig {
        Objects.requireNonNull(bindHost, "bindHost");
        if (bindHost.isBlank()) {
            throw new IllegalArgumentException("direct-bridge.bind host must not be blank");
        }
        if (bindPort < 1 || bindPort > 65_535) {
            throw new IllegalArgumentException("direct-bridge.bind port must be between 1 and 65535, got " + bindPort);
        }
        if (targetHost != null) {
            if (targetHost.isBlank()) {
                throw new IllegalArgumentException("direct-bridge.target host must not be blank");
            }
            if (targetPort < 1 || targetPort > 65_535) {
                throw new IllegalArgumentException(
                    "direct-bridge.target port must be between 1 and 65535, got " + targetPort
                );
            }
        } else if (targetPort != 0) {
            throw new IllegalArgumentException("direct-bridge.target port must be 0 when targetHost is null");
        }
    }

    public static DirectBridgeConfig disabled() {
        return new DirectBridgeConfig(false, DEFAULT_BIND_HOST, DEFAULT_BIND_PORT, null, 0);
    }

    public boolean hasExplicitTarget() {
        return targetHost != null;
    }

    public InetSocketAddress bindSocketAddress() {
        return new InetSocketAddress(bindHost, bindPort);
    }

    public InetSocketAddress explicitTargetAddress() {
        if (targetHost == null) {
            return null;
        }
        return new InetSocketAddress(targetHost, targetPort);
    }

    public String bindDisplay() {
        return formatHostPort(bindHost, bindPort);
    }

    public String targetDisplay() {
        return targetHost == null ? "auto" : formatHostPort(targetHost, targetPort);
    }

    public static String formatHostPort(String host, int port) {
        if (host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }
}
