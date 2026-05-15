package com.bit.coin.p2p.production;

public record P2PAddress(
        String rawAddress,
        String ipType,
        String ipAddress,
        int port,
        String peerIdBase58,
        String peerIdHex
) {
    public String endpointKey() {
        return ipAddress + ":" + port;
    }
}
