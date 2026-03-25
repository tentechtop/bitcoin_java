package com.bit.coin.utils;

import lombok.Data;

@Data
public class KeyInfo {
    private final byte[] privateKey;
    private final byte[] publicKey;
    private final String address;
    private final String path;

    public KeyInfo(byte[] privateKey, byte[] publicKey, String address, String path) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.address = address;
        this.path = path;
    }

    // Getters
    public byte[] getPrivateKey() { return privateKey; }
    public byte[] getPublicKey() { return publicKey; }
    public String getAddress() { return address; }
    public String getPath() { return path; }
}
