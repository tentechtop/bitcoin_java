package com.bit.coin.p2p.production;

import com.bit.coin.config.SystemConfig;
import com.bit.coin.p2p.peer.Peer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

public final class P2PSelfNode {
    private P2PSelfNode() {
    }

    public static boolean isSelfAddress(P2PAddress address) {
        return address != null
                && (isSelfPeerId(address.peerIdHex()) || isSelfEndpoint(address.ipAddress(), address.port()));
    }

    public static boolean isSelfPeerId(String peerId) {
        String selfPeerId = selfPeerIdHex();
        if (selfPeerId == null || peerId == null || peerId.isBlank()) {
            return false;
        }
        try {
            return selfPeerId.equals(P2PAddressValidator.normalizePeerId(peerId));
        } catch (RuntimeException e) {
            return selfPeerId.equals(peerId.trim().toLowerCase(Locale.ROOT));
        }
    }

    public static boolean isSelfPeerId(byte[] peerId) {
        Peer self = SystemConfig.SelfPeer;
        return self != null
                && self.getId() != null
                && peerId != null
                && Arrays.equals(self.getId(), peerId);
    }

    public static boolean isSelfEndpoint(String ipAddress, int port) {
        Peer self = SystemConfig.SelfPeer;
        if (self == null || port <= 0 || self.getPort() != port) {
            return false;
        }
        return isLocalAddress(ipAddress) || sameAddress(ipAddress, self.getAddress());
    }

    public static String selfPeerIdHex() {
        Peer self = SystemConfig.SelfPeer;
        if (self == null || self.getId() == null) {
            return null;
        }
        return HexFormat.of().formatHex(self.getId());
    }

    private static boolean isLocalAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            return address.isLoopbackAddress() || address.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean sameAddress(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(left).equals(InetAddress.getByName(right));
        } catch (UnknownHostException e) {
            return left.trim().equalsIgnoreCase(right.trim());
        }
    }
}
