package com.bit.coin.p2p.production;

import java.util.concurrent.atomic.AtomicLong;

public class P2PFlowController {

    public static final long DEFAULT_RECEIVE_WINDOW = 8L * 1024 * 1024;  // 收发双方的初始接收额度，单位为字节；对端额度随确认更新

    private final long localMaxReceiveWindow;
    private final AtomicLong localReceiveWindow;
    private final AtomicLong remoteReceiveWindow;
    private final AimdCongestionController congestionController;

    public P2PFlowController() {
        this(DEFAULT_RECEIVE_WINDOW, new AimdCongestionController());
    }

    public P2PFlowController(long receiveWindow, AimdCongestionController congestionController) {
        if (receiveWindow <= 0) {
            throw new IllegalArgumentException("receiveWindow must be positive");
        }
        this.localMaxReceiveWindow = receiveWindow;
        this.localReceiveWindow = new AtomicLong(receiveWindow);
        this.remoteReceiveWindow = new AtomicLong(receiveWindow);
        this.congestionController = congestionController;
    }

    public boolean tryReserveOutbound(long bytes) {
        return congestionController.tryReserve(bytes, remoteReceiveWindow.get());
    }

    /** 接收数据前扣减本地窗口，处理完成后调用 releaseInbound 归还。 */
    public boolean tryAcquireInbound(long bytes) {
        if (bytes <= 0) {
            return false;
        }
        while (true) {
            long current = localReceiveWindow.get();
            if (current < bytes) {
                return false;
            }
            if (localReceiveWindow.compareAndSet(current, current - bytes)) {
                return true;
            }
        }
    }

    public void releaseInbound(long bytes) {
        if (bytes <= 0) {
            return;
        }
        while (true) {
            long current = localReceiveWindow.get();
            long next = Math.min(localMaxReceiveWindow, current + bytes);
            if (localReceiveWindow.compareAndSet(current, next)) {
                return;
            }
        }
    }

    public void updateRemoteReceiveWindow(long remoteWindow) {
        remoteReceiveWindow.set(Math.max(0, remoteWindow));
    }

    public void onAck(long ackedBytes, long updatedRemoteWindow) {
        updateRemoteReceiveWindow(updatedRemoteWindow);
        congestionController.onAck(ackedBytes);
    }

    public void onLoss(long lostBytes) {
        congestionController.onLoss(lostBytes);
    }

    public void onTimeout() {
        congestionController.onTimeout();
    }

    public FlowControlSnapshot snapshot() {
        long remoteWindow = remoteReceiveWindow.get();
        return new FlowControlSnapshot(
                localReceiveWindow.get(),
                localMaxReceiveWindow,
                remoteWindow,
                congestionController.snapshot(remoteWindow));
    }
}
