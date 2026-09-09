package com.bit.coin.blockchain;

/** A candidate exceeds local processing limits before commit; stored chain state remains healthy. */
public final class ChainStateLimitException extends RuntimeException {
    public ChainStateLimitException(String message) {
        super(message);
    }
}
