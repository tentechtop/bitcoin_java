package com.bit.coin.p2p.production;

public record P2PTransferErrorRecord(
        String peerId,
        String remoteAddress,
        P2PErrorCode errorCode,
        String message,
        long dataId,
        long connectionId,
        long occurredAtMillis,
        String exceptionClass
) {
    public static P2PTransferErrorRecord now(String peerId,
                                             String remoteAddress,
                                             P2PErrorCode errorCode,
                                             String message,
                                             long dataId,
                                             long connectionId,
                                             Throwable cause) {
        return new P2PTransferErrorRecord(
                peerId,
                remoteAddress,
                errorCode,
                message,
                dataId,
                connectionId,
                System.currentTimeMillis(),
                cause == null ? "" : cause.getClass().getName()
        );
    }
}
