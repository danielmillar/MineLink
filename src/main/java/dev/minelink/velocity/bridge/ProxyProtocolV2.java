package dev.minelink.velocity.bridge;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Objects;

public final class ProxyProtocolV2 {

    private static final byte[] SIGNATURE = {
        0x0D, 0x0A, 0x0D, 0x0A,
        0x00, 0x0D, 0x0A, 0x51,
        0x55, 0x49, 0x54, 0x0A
    };

    private static final byte VERSION_COMMAND_PROXY = 0x21;
    private static final byte FAMILY_PROTO_TCP4 = 0x11;
    private static final byte FAMILY_PROTO_TCP6 = 0x21;
    private static final int IPV4_ADDR_PAYLOAD_LEN = 12;
    private static final int IPV6_ADDR_PAYLOAD_LEN = 36;

    private ProxyProtocolV2() {}

    public static byte[] encode(InetSocketAddress source, InetSocketAddress destination) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        InetAddress srcAddr = source.getAddress();
        InetAddress dstAddr = destination.getAddress();
        if (srcAddr == null || dstAddr == null) {
            throw new IllegalArgumentException("Both source and destination addresses must be resolved");
        }
        int srcPort = source.getPort();
        int dstPort = destination.getPort();
        if (srcPort < 0 || srcPort > 65_535 || dstPort < 0 || dstPort > 65_535) {
            throw new IllegalArgumentException("Port out of range: " + srcPort + " -> " + dstPort);
        }

        byte[] srcV4 = toIpv4OrNull(srcAddr);
        if (srcV4 != null) {
            byte[] dstV4 = toIpv4OrNull(dstAddr);
            if (dstV4 == null) {
                dstV4 = new byte[4];
            }
            byte[] header = new byte[16 + IPV4_ADDR_PAYLOAD_LEN];
            System.arraycopy(SIGNATURE, 0, header, 0, SIGNATURE.length);
            header[12] = VERSION_COMMAND_PROXY;
            header[13] = FAMILY_PROTO_TCP4;
            header[14] = (byte) ((IPV4_ADDR_PAYLOAD_LEN >>> 8) & 0xFF);
            header[15] = (byte) (IPV4_ADDR_PAYLOAD_LEN & 0xFF);
            System.arraycopy(srcV4, 0, header, 16, 4);
            System.arraycopy(dstV4, 0, header, 20, 4);
            header[24] = (byte) ((srcPort >>> 8) & 0xFF);
            header[25] = (byte) (srcPort & 0xFF);
            header[26] = (byte) ((dstPort >>> 8) & 0xFF);
            header[27] = (byte) (dstPort & 0xFF);
            return header;
        }

        byte[] srcV6 = toIpv6(srcAddr);
        byte[] dstV6 = toIpv6(dstAddr);
        byte[] header = new byte[16 + IPV6_ADDR_PAYLOAD_LEN];
        System.arraycopy(SIGNATURE, 0, header, 0, SIGNATURE.length);
        header[12] = VERSION_COMMAND_PROXY;
        header[13] = FAMILY_PROTO_TCP6;
        header[14] = (byte) ((IPV6_ADDR_PAYLOAD_LEN >>> 8) & 0xFF);
        header[15] = (byte) (IPV6_ADDR_PAYLOAD_LEN & 0xFF);
        System.arraycopy(srcV6, 0, header, 16, 16);
        System.arraycopy(dstV6, 0, header, 32, 16);
        header[48] = (byte) ((srcPort >>> 8) & 0xFF);
        header[49] = (byte) (srcPort & 0xFF);
        header[50] = (byte) ((dstPort >>> 8) & 0xFF);
        header[51] = (byte) (dstPort & 0xFF);
        return header;
    }

    private static byte[] toIpv4OrNull(InetAddress addr) {
        if (addr instanceof Inet4Address) {
            return addr.getAddress();
        }
        if (addr instanceof Inet6Address) {
            byte[] bytes = addr.getAddress();
            if (isIpv4MappedIpv6(bytes)) {
                return Arrays.copyOfRange(bytes, 12, 16);
            }
        }
        return null;
    }

    private static byte[] toIpv6(InetAddress addr) {
        if (addr instanceof Inet6Address) {
            return addr.getAddress();
        }
        byte[] v4 = addr.getAddress();
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        System.arraycopy(v4, 0, mapped, 12, 4);
        return mapped;
    }

    private static boolean isIpv4MappedIpv6(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }
}
