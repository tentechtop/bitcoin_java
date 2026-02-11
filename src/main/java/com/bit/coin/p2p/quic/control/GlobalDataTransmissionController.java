package com.bit.coin.p2p.quic.control;

import com.bit.coin.p2p.quic.QuicConstants;
import com.bit.coin.p2p.quic.QuicFrame;
import com.bit.coin.p2p.quic.SendQuicData;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class GlobalDataTransmissionController {

    // 帧发送任务队列（优先级：FIFO，线程安全）
    private BlockingQueue<QuicFrame> frameSendQueue;

    // 发送控制核心参数
    private int sendRate; // 每秒发送的最大帧数（控制速率核心）
    private long sendIntervalMs; // 每帧发送间隔（ms），由sendRate计算得出

    // 重试相关参数
    private static final int MAX_RETRY_TIMES = 5; // 最大重试次数
    private static final long BASE_RETRY_DELAY_MS = 100; // 基础重试延迟（ms）

    // 发送线程相关
    private ScheduledExecutorService sendExecutor;
    private AtomicBoolean isRunning;
    private AtomicInteger currentQueueSize; // 当前帧队列大小监控

    // 单例实例
    private static volatile GlobalDataTransmissionController INSTANCE;

    /**
     * 数据发送任务封装（整段数据+连接信息）
     */
    @lombok.Data
    private static class DataSendTask {
        private long connectionId;
        private long dataId;
        private byte[] data;
        private InetSocketAddress remoteAddress;
        private int maxFramePayload; // 单帧最大载荷
    }





}