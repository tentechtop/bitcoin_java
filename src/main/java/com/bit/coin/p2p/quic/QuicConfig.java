package com.bit.coin.p2p.quic;

/**
 * QUIC网络框架配置常量类
 * 集中管理所有配置参数，便于维护和调优
 */
public class QuicConfig {

    // ==================== 帧相关配置 ====================
    /** 单帧最大载荷大小（字节） */
    public static final int MAX_FRAME_PAYLOAD = 1400;

    /** 单次数据最大帧数（8192帧 * 1400字节 ≈ 11MB） */
    public static final int MAX_FRAMES_PER_DATA = 8192;

    /** 帧固定头部长度（字节） */
    public static final int FRAME_HEADER_LENGTH = 29; // 8+8+4+1+4+4

    // ==================== 超时配置 ====================
    /** 全局数据传输超时时间（毫秒） */
    public static final long GLOBAL_TIMEOUT_MS = 5000L;

    /** 连接过期超时时间（毫秒） */
    public static final long CONNECTION_EXPIRE_TIMEOUT = 5000L;

    /** 请求响应超时时间（毫秒） */
    public static final long REQUEST_TIMEOUT_MS = 500L;

    // ==================== 重传配置 ====================
    /** 首次重传延迟时间（毫秒） */
    public static final long FIRST_RETRANSMIT_DELAY_MS = 500L;

    /** 基础重传间隔（毫秒） */
    public static final long RETRANSMIT_BASE_INTERVAL_MS = 20L;

    /** 最大重传延迟（毫秒） */
    public static final long RETRANSMIT_MAX_DELAY_MS = 2000L;

    /** 最大重传轮数 */
    public static final int MAX_RETRANSMIT_ROUNDS = 5;

    /** 单次重传最大帧数（避免突发流量） */
    public static final int MAX_RETRANSMIT_FRAMES_PER_ROUND = 128;

    // ==================== ACK配置 ====================
    /** 批量ACK触发帧数 */
    public static final int ACK_BATCH_SIZE = 64;

    /** ACK最大延迟时间（毫秒） */
    public static final long ACK_MAX_DELAY_MS = 100L;

    // ==================== 心跳配置 ====================
    /** 出站连接心跳间隔（毫秒） */
    public static final long OUTBOUND_HEARTBEAT_INTERVAL = 1000L;

    /** 入站连接检查间隔（毫秒） */
    public static final long INBOUND_CHECK_INTERVAL = 1000L;

    // ==================== 重试配置 ====================
    /** 本地发送最大重试次数 */
    public static final int LOCAL_RETRY_MAX = 3;

    /** 本地发送重试间隔（毫秒） */
    public static final long LOCAL_RETRY_INTERVAL_MS = 50L;

    /** 网络重试最大次数 */
    public static final int NET_RETRY_MAX = 2;

    /** 网络重试间隔（毫秒） */
    public static final long NET_RETRY_INTERVAL_MS = 200L;

    // ==================== 缓存配置 ====================
    /** 请求响应Future缓存最大容量 */
    public static final long RESPONSE_CACHE_MAX_SIZE = 1_000_000L;

    /** 请求响应Future缓存过期时间（秒） */
    public static final long RESPONSE_CACHE_EXPIRE_SECONDS = 5L;

    /** 已发送数据ID缓存最大容量 */
    public static final long SEND_CACHE_MAX_SIZE = 5_000_000L;

    /** 已发送数据ID缓存过期时间（秒） */
    public static final long SEND_CACHE_EXPIRE_SECONDS = 5L;

    /** 已接收数据ID缓存最大容量 */
    public static final long RECEIVE_CACHE_MAX_SIZE = 5_000_000L;

    /** 已接收数据ID缓存过期时间（秒） */
    public static final long RECEIVE_CACHE_EXPIRE_SECONDS = 5L;

    // ==================== 消息队列配置 ====================
    /** 消息队列容量 */
    public static final int MESSAGE_QUEUE_CAPACITY = 10000;

    /** 消息推送超时时间（毫秒） */
    public static final long MESSAGE_PUSH_TIMEOUT_MS = 100L;

    // ==================== 对象池配置 ====================
    /** QuicFrame对象池最大容量 */
    public static final int FRAME_POOL_MAX_CAPACITY = 64 * 1024;

    // ==================== 定时器配置 ====================
    /** 心跳时间轮精度（毫秒） */
    public static final long HEARTBEAT_TIMER_TICK_MS = 40L;

    /** 心跳时间轮槽数 */
    public static final int HEARTBEAT_TIMER_WHEEL_SIZE = 1 << 14;

    /** 全局定时器精度（毫秒） */
    public static final long GLOBAL_TIMER_TICK_MS = 40L;

    /** 全局定时器槽数 */
    public static final int GLOBAL_TIMER_WHEEL_SIZE = 1024;

    // ==================== 安全配置 ====================
    /** 最大数据包大小（字节，防止OOM攻击） */
    public static final int MAX_PACKET_SIZE = 65536;

    /** 连接ID生成算法 */
    public static enum ConnectionIdGenerator {
        SNOWFLAKE, // 雪花算法
        RANDOM     // 随机生成
    }

    /** 当前使用的连接ID生成算法 */
    public static final ConnectionIdGenerator CONNECTION_ID_GENERATOR = ConnectionIdGenerator.SNOWFLAKE;

    // 私有构造函数，防止实例化
    private QuicConfig() {}

    /**
     * 根据总帧数动态计算全局超时时间
     * @param totalFrames 总帧数
     * @return 超时时间（毫秒）
     */
    public static long calculateGlobalTimeout(int totalFrames) {
        // 基础超时 + 每帧额外时间（防止大量数据传输超时）
        return GLOBAL_TIMEOUT_MS + (totalFrames * 2L);
    }

    /**
     * 根据网络状况动态调整重传延迟（预留接口）
     * @param rtt 往返时间（毫秒）
     * @return 调整后的重传延迟（毫秒）
     */
    public static long calculateAdaptiveRetransmitDelay(long rtt) {
        // 使用RTT的3倍作为基础延迟
        return Math.max(FIRST_RETRANSMIT_DELAY_MS, rtt * 3);
    }
}
