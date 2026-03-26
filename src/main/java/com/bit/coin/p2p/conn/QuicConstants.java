package com.bit.coin.p2p.conn;

import com.bit.coin.utils.SnowflakeIdGenerator;
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

    /** 最大重传次数（可根据业务调整，建议3次） */
    public static final int MAX_RETRY_COUNT = 3;

    /** 重传间隔（150ms，固定） */
    public static final long RETRY_INTERVAL_MS = 150;


    //将完整的二进制数据放在缓存中 等待消费者消费 一次全部取走
    private static final BlockingQueue<QuicMsg> MSG_QUEUE = new LinkedBlockingQueue<>(1000_0);

    /**
     * 推送完整Quic消息到静态队列（非阻塞，避免阻塞Netty IO线程）
     * @param msg 完整消息体
     * @return 是否推送成功
     */
    public static boolean pushCompleteMsg(QuicMsg msg) {
        try {
            // 非阻塞推送：队列满时等待100ms，仍失败则返回false
            boolean success = MSG_QUEUE.offer(msg, 100, TimeUnit.MILLISECONDS);
            if (!success) {
                log.error("[消息推送失败] 队列已满");
            }
            return success;
        } catch (InterruptedException e) {
            log.error("[消息推送中断] ", e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ========== 消费者API：从静态队列取消息（供Spring消费者Bean调用） ==========
    /**
     * 阻塞取消息：无数据则挂起，有数据立即返回（核心，CPU≈0消耗）
     */
    public static QuicMsg takeMsg() throws InterruptedException {
        return MSG_QUEUE.take();
    }
    /**
     * 带超时的取消息：避免永久阻塞（可选）
     */
    public static QuicMsg pollMsg(long timeout, TimeUnit unit) throws InterruptedException {
        return MSG_QUEUE.poll(timeout, unit);
    }

    // ========== 辅助：获取队列当前长度（供监控用） ==========
    public static int getQueueSize() {
        return MSG_QUEUE.size();
    }
    // ========== 新增：批量提取全部消息 ==========
    /**
     * 原子性批量提取队列中所有可用消息（非阻塞，线程安全）
     * @return 消息列表（队列为空时返回空列表）
     */
    public static List<QuicMsg> drainAllMsg() {
        List<QuicMsg> msgList = new ArrayList<>();
        // drainTo：原子性将队列中所有元素移到集合，返回移走的数量
        MSG_QUEUE.drainTo(msgList);
        return msgList;
    }


    public static final int MAX_FRAME = 8192;//单次最大发送8192帧 当一帧承载1K数据 8192帧 约等于8M数据 极限是一帧1400字节


    //连接ID或数据ID生成器
    public static SnowflakeIdGenerator ID_Generator = new SnowflakeIdGenerator();

    /**
     * 请求响应Future缓存：最大容量100万个，30秒过期（请求超时后自动清理，避免内存泄漏）
     * Key：请求ID，Value：响应Future
     * 16字节的UUIDV7 - > CompletableFuture<byte[]>
     */
    public static Cache<String, CompletableFuture<QuicMsg>> MSG_RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

    /**
     * 请求响应Future缓存：最大容量100万个，30秒过期（请求超时后自动清理，避免内存泄漏）
     * 标志->CompletableFuture
     */
    public static Cache<Long, CompletableFuture<QuicFrame>> QUICFRAME_RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();



    public static Cache<Long, CompletableFuture<byte[]>> BYTE_RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

    //已经存在的资源 hashHex -> 资源类型
    public static Cache<String, Integer> EXIST_RESOURCE_CACHE  = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .recordStats()
            .build();

}
