package com.bit.coin.p2p.production;

public record P2PAddress(
        String rawAddress,
        String ipType,
        String ipAddress,
        int port,
        String peerIdBase58,
        String peerIdHex,
        String relayAddress
) {
    public P2PAddress(String rawAddress, String ipType, String ipAddress, int port, String peerIdBase58, String peerIdHex) {
        this(rawAddress, ipType, ipAddress, port, peerIdBase58, peerIdHex, null);
    }
    public boolean isRelayed() { return relayAddress != null; }
    public String endpointKey() {
        return ipAddress + ":" + port;
    }
}
