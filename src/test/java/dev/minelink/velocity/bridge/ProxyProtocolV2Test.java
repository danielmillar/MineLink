package dev.minelink.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class ProxyProtocolV2Test {

    private static final byte[] EXPECTED_SIGNATURE = {
        0x0D, 0x0A, 0x0D, 0x0A,
        0x00, 0x0D, 0x0A, 0x51,
        0x55, 0x49, 0x54, 0x0A
    };

    @Test
    void encodesIpv4TcpHeaderWithUnsignedPorts() throws Exception {
        InetSocketAddress src = new InetSocketAddress(InetAddress.getByName("203.0.113.42"), 54321);
        InetSocketAddress dst = new InetSocketAddress(InetAddress.getByName("198.51.100.7"), 25566);

        byte[] header = ProxyProtocolV2.encode(src, dst);
        assertEquals(28, header.length);

        byte[] sig = new byte[12];
        System.arraycopy(header, 0, sig, 0, 12);
        assertArrayEquals(EXPECTED_SIGNATURE, sig);

        assertEquals(0x21, header[12] & 0xFF);
        assertEquals(0x11, header[13] & 0xFF);
        assertEquals(12, ((header[14] & 0xFF) << 8) | (header[15] & 0xFF));

        assertArrayEquals(new byte[] {(byte) 203, 0, 113, 42}, slice(header, 16, 4));
        assertArrayEquals(new byte[] {(byte) 198, 51, 100, 7}, slice(header, 20, 4));
        assertEquals(54321, ((header[24] & 0xFF) << 8) | (header[25] & 0xFF));
        assertEquals(25566, ((header[26] & 0xFF) << 8) | (header[27] & 0xFF));
    }

    @Test
    void encodesIpv6TcpHeader() throws Exception {
        InetAddress srcIp = InetAddress.getByName("2001:db8::1");
        InetAddress dstIp = InetAddress.getByName("::1");
        byte[] header = ProxyProtocolV2.encode(new InetSocketAddress(srcIp, 40000), new InetSocketAddress(dstIp, 25565));

        assertEquals(52, header.length);
        assertArrayEquals(EXPECTED_SIGNATURE, slice(header, 0, 12));
        assertEquals(0x21, header[12] & 0xFF);
        assertEquals(0x21, header[13] & 0xFF);
        assertEquals(36, ((header[14] & 0xFF) << 8) | (header[15] & 0xFF));
        assertArrayEquals(srcIp.getAddress(), slice(header, 16, 16));
        assertArrayEquals(dstIp.getAddress(), slice(header, 32, 16));
        assertEquals(40000, ((header[48] & 0xFF) << 8) | (header[49] & 0xFF));
        assertEquals(25565, ((header[50] & 0xFF) << 8) | (header[51] & 0xFF));
    }

    @Test
    void normalizesMixedAddressFamiliesCleanly() throws Exception {
        InetSocketAddress ipv4Src = new InetSocketAddress(InetAddress.getByName("192.0.2.5"), 12345);
        InetSocketAddress ipv6Dst = new InetSocketAddress(InetAddress.getByName("::1"), 25566);
        byte[] v4Header = ProxyProtocolV2.encode(ipv4Src, ipv6Dst);
        assertEquals(28, v4Header.length);
        assertEquals(0x11, v4Header[13] & 0xFF);
        assertArrayEquals(new byte[] {(byte) 192, 0, 2, 5}, slice(v4Header, 16, 4));
        assertArrayEquals(new byte[] {0, 0, 0, 0}, slice(v4Header, 20, 4));

        InetSocketAddress ipv6Src = new InetSocketAddress(InetAddress.getByName("2001:db8::9"), 22222);
        InetSocketAddress ipv4Dst = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25566);
        byte[] v6Header = ProxyProtocolV2.encode(ipv6Src, ipv4Dst);
        assertEquals(52, v6Header.length);
        assertEquals(0x21, v6Header[13] & 0xFF);
        assertArrayEquals(
            new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 127, 0, 0, 1},
            slice(v6Header, 32, 16)
        );
    }

    @Test
    void rejectsUnresolvedAddresses() throws Exception {
        InetSocketAddress resolved = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25565);
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("example.invalid", 25565);
        assertThrows(IllegalArgumentException.class, () -> ProxyProtocolV2.encode(unresolved, resolved));
        assertThrows(IllegalArgumentException.class, () -> ProxyProtocolV2.encode(resolved, unresolved));
    }

    private static byte[] slice(byte[] src, int offset, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, offset, out, 0, len);
        return out;
    }
}
