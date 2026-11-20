package com.bit.coin.blockchain;

/** 候选区块在提交前超出本地处理上限，已存储的链状态仍然有效。 */
public final class ChainStateLimitException extends RuntimeException {

    public ChainStateLimitException(String message) {
        super(message);
    }
}
