package com.bit.coin.database;

@FunctionalInterface
public interface KeyValueHandler {
    // 返回 false 表示停止迭代
    boolean handle(byte[] key, byte[] value);
}
