package com.bit.coin.p2p.production;

import org.bitcoinj.core.Base58;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class P2PAddressValidator {
    private static final Pattern HEX_PEER_ID = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern IPV4_SEGMENT = Pattern.compile("^\\d{1,3}$");

    private P2PAddressValidator() {
    }

    public static P2PAddress parseQuicAddress(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new IllegalArgumentException("multiAddress is required");
        }

        String[] segments = rawAddress.trim().split("/");
        if (segments.length != 7 || !segments[0].isEmpty()) {
            throw new IllegalArgumentException("multiAddress must use /ip4/<ip>/quic/<port>/p2p/<peerId>");
        }

        String ipType = segments[1].toLowerCase(Locale.ROOT);
        String ipAddress = segments[2];
        String protocol = segments[3].toLowerCase(Locale.ROOT);
        String portText = segments[4];
        String p2pMarker = segments[5].toLowerCase(Locale.ROOT);
        String peerIdBase58 = segments[6];

        if (!"ip4".equals(ipType) && !"ip6".equals(ipType)) {
            throw new IllegalArgumentException("unsupported ip type: " + segments[1]);
        }
        validateIp(ipType, ipAddress);
        if (!"quic".equals(protocol)) {
            throw new IllegalArgumentException("only quic multiAddress is supported");
        }
        if (!"p2p".equals(p2pMarker)) {
            throw new IllegalArgumentException("multiAddress must contain /p2p/<peerId>");
        }

        int port = parsePort(portText);
        String peerIdHex = normalizePeerId(peerIdBase58);
        return new P2PAddress(rawAddress.trim(), ipType, ipAddress, port, peerIdBase58, peerIdHex);
    }

    public static String normalizePeerId(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            throw new IllegalArgumentException("peerId is required");
        }
        String trimmed = peerId.trim();
        if (HEX_PEER_ID.matcher(trimmed).matches()) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] decoded = Base58.decode(trimmed);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("peerId must decode to 32 bytes");
            }
            return HexFormat.of().formatHex(decoded);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("peerId must be 64-char hex or Base58 encoded 32 bytes", e);
        }
    }

    public static void validateIp(String ipType, String ipAddress) {
        if ("ip4".equals(ipType)) {
            validateIpv4(ipAddress);
            return;
        }
        if ("ip6".equals(ipType)) {
            validateIpv6(ipAddress);
            return;
        }
        throw new IllegalArgumentException("unsupported ip type: " + ipType);
    }

    private static void validateIpv4(String ipAddress) {
        String[] parts = ipAddress == null ? new String[0] : ipAddress.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("invalid ip4 address: " + ipAddress);
        }
        for (String part : parts) {
            if (!IPV4_SEGMENT.matcher(part).matches()) {
                throw new IllegalArgumentException("invalid ip4 address: " + ipAddress);
            }
            int value = Integer.parseInt(part);
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("invalid ip4 address: " + ipAddress);
            }
        }
    }

    private static void validateIpv6(String ipAddress) {
        if (ipAddress == null || !ipAddress.contains(":")) {
            throw new IllegalArgumentException("invalid ip6 address: " + ipAddress);
        }
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            if (!(address instanceof Inet6Address)) {
                throw new IllegalArgumentException("invalid ip6 address: " + ipAddress);
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid ip6 address: " + ipAddress, e);
        }
    }

    private static int parsePort(String portText) {
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port must be numeric: " + portText, e);
        }
    }
}
