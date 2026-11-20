package com.bit.coin.blockchain;

import com.bit.coin.utils.UInt256;

public record ChainTipSnapshot(int height, byte[] hash, UInt256 chainWork) {

    public ChainTipSnapshot {
        hash = hash == null ? null : hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash == null ? null : hash.clone();
    }
}
