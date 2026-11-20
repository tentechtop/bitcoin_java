package com.bit.coin.p2p.conn;

import com.bit.coin.utils.SnowflakeIdGenerator;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class QuicConstants {

    /** 整条消息的重试次数上限，单个分片另行计数。 */
    public static final int MAX_RETRY_COUNT = 3;

    /** 同一消息两次重试的最短间隔，单位为毫秒。 */
    public static final long RETRY_INTERVAL_MS = 150;

    // 消费队列只保留已解码消息，按原始消息大小计入预算。
    private static final BlockingQueue<QuicMsg> MSG_QUEUE = new LinkedBlockingQueue<>(256);

    /** 按原始报文长度累计队列占用，出队时扣回相应字节数。 */
    private static final java.util.concurrent.atomic.AtomicLong QUEUED_BYTES = new java.util.concurrent.atomic.AtomicLong();
    private static final long MAX_QUEUE_BYTES = 64L * 1024 * 1024;

    /** 非阻塞入队；消息过大、队列已满或字节预算不足时返回 false。 */
    public static boolean pushCompleteMsg(QuicMsg msg) {
        if (msg == null || msg.wireLength() > 8_100_000) {
            return false;
        }
        int length = msg.wireLength();
        long total = QUEUED_BYTES.addAndGet(length);
        if (total > MAX_QUEUE_BYTES || !MSG_QUEUE.offer(msg)) {
            QUEUED_BYTES.addAndGet(-length);
            return false;
        }
        return true;
    }

    private static QuicMsg released(QuicMsg msg) {
        if (msg != null) {
            QUEUED_BYTES.addAndGet(-msg.wireLength());
        }
        return msg;
    }

    public static long queuedBytes() {
        return QUEUED_BYTES.get();
    }

    /** 阻塞等待一条消息，出队时归还字节预算。 */
    public static QuicMsg takeMsg() throws InterruptedException {
        return released(MSG_QUEUE.take());
    }

    /** 最多等待指定时长，超时返回 null。 */
    public static QuicMsg pollMsg(long timeout, TimeUnit unit) throws InterruptedException {
        return released(MSG_QUEUE.poll(timeout, unit));
    }

    public static int getQueueSize() {
        return MSG_QUEUE.size();
    }

    /** 非阻塞取出最多 16 条消息，并归还相应的队列预算。 */
    public static List<QuicMsg> drainAllMsg() {
        List<QuicMsg> msgList = new ArrayList<>();
        MSG_QUEUE.drainTo(msgList, 16);
        msgList.forEach(QuicConstants::released);
        return msgList;
    }

    public static final int MAX_FRAME = 8192;  // 单条消息允许拆分的最多帧数

    //连接ID或数据ID生成器
    public static SnowflakeIdGenerator ID_Generator = new SnowflakeIdGenerator();

    /** 将请求 ID 绑定到预期响应的连接，供响应分发时核对。 */
    public static final Cache<String, Long> MSG_EXPECTED_CONNECTIONS = Caffeine.newBuilder()
            .maximumSize(10000).expireAfterWrite(30, TimeUnit.SECONDS).build();

    // 用请求 ID 找到等待该响应的调用方。
    public static Cache<String, CompletableFuture<P2PMessage>> MSG_RESPONSE_FUTURECACHE = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            // 缓存条目到期前持有强引用
            // .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

    public static Cache<Long, CompletableFuture<byte[]>> BYTE_RESPONSE_FUTURECACHE = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            // 缓存条目到期前持有强引用
            // .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

    //已经存在的资源 hashHex -> 资源类型
    public static Cache<String, Integer> EXIST_RESOURCE_CACHE = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .recordStats()
            .build();

}
