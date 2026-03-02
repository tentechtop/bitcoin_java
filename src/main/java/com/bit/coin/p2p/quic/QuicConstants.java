package com.bit.coin.p2p.quic;


import com.bit.coin.utils.SnowflakeIdGenerator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class QuicConstants {

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


    /**
     * 请求响应Future缓存：最大容量100万个，30秒过期（请求超时后自动清理，避免内存泄漏）
     * 标志->CompletableFuture
     */
    public static Cache<Long, CompletableFuture<Object>> QUIC_RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

    public static final ByteBufAllocator ALLOCATOR = ByteBufAllocator.DEFAULT;
    //连接ID或数据ID生成器
    public static SnowflakeIdGenerator generator = new SnowflakeIdGenerator();

    //单帧载荷
    public static final int MAX_FRAME = 8192;//单次最大发送8192帧 当一帧承载1K数据 8192帧 约等于8M数据 极限是一帧1400字节

    // 出站连接主动心跳间隔
    public static final long OUTBOUND_HEARTBEAT_INTERVAL = 1000L;
    // 连接过期阈值（统一1000ms无活动）
    public static final long CONNECTION_EXPIRE_TIMEOUT = 5000L;
    private QuicConstants() {}


    //连接ID -> 数据ID
    public static final Map<Long, Long> connectSendMap = new HashMap<>();
    public static final Map<Long, Long> connectReceiveMap = new HashMap<>();

    //数据ID-> 数据
    public static Map<Long, SendQuicData> sendMap = new HashMap<>();//发送中的数据缓存 数据收到全部的ACK后释放掉 发送帧50ms后未收到ACK则重发 重发三次未收到ACK则放弃并清除数据 下线节点
    public static  Map<Long, ReceiveQuicData> receiveMap = new HashMap<>();//接收中的数据缓存 数据完整后释放掉


    //已经发送的数据ID 缓存5百万条 8字节 500万 1秒过期
    public static Cache<Long,Long> S_CACHE  = Caffeine.newBuilder()
            .maximumSize(5_000_000)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();

    //已经接收的数据ID 缓存5百万条 8字节 500万
    public static Cache<Long,Long> R_CACHE  = Caffeine.newBuilder()
            .maximumSize(5_000_000)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();


    public static final HashedWheelTimer HEARTBEAT_TIMER = new HashedWheelTimer(
            new DefaultThreadFactory("quic-heartbeat-timer", true),
            40, TimeUnit.MILLISECONDS, // 10ms精度
            1<<14 // 槽位数（2的幂，优化性能）
    );

    public static final Timer GLOBAL_TIMER = new HashedWheelTimer(
            new DefaultThreadFactory("quic-send-timer", true),
            40, TimeUnit.MILLISECONDS,
            1024 // 时间轮槽数
    );



    // ====================== 发送数据操作方法 ======================
    /**
     * 1. 获取指定连接下的全部发送数据
     * @param connectId 连接ID（不可为null）
     * @return 该连接下所有有效的发送数据（不可变列表，避免外部修改）
     */
    public static List<SendQuicData> getAllSendDataByConnectId(Long connectId) {
        // 空值校验
        if (connectId == null) {
            return Collections.emptyList();
        }

        List<SendQuicData> sendDataList = new ArrayList<>();
        // 遍历连接-数据ID映射，筛选指定连接的所有数据
        for (Map.Entry<Long, Long> entry : connectSendMap.entrySet()) {
            if (connectId.equals(entry.getKey())) {
                Long dataId = entry.getValue();
                SendQuicData sendData = sendMap.get(dataId);
                // 仅添加非空数据（避免已被清理但映射未删除的情况）
                if (sendData != null) {
                    sendDataList.add(sendData);
                }
            }
        }
        // 返回不可变列表，防止外部修改内部数据
        return Collections.unmodifiableList(sendDataList);
    }

    /**
     * 2. 删除指定连接下的全部发送数据
     * @param connectId 连接ID（不可为null）
     */
    public static void deleteAllSendDataByConnectId(Long connectId) {
        // 空值校验
        if (connectId == null) {
            return;
        }

        // 步骤1：先收集该连接下的所有数据ID（避免遍历中Map结构变化）
        List<Long> dataIds = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : connectSendMap.entrySet()) {
            if (connectId.equals(entry.getKey())) {
                dataIds.add(entry.getValue());
            }
        }

        // 步骤2：删除数据缓存中的对应数据
        for (Long dataId : dataIds) {
            SendQuicData remove = sendMap.remove(dataId);
            remove.release();
        }

        // 步骤3：删除连接-数据ID的映射关系
        connectSendMap.keySet().removeIf(connectId::equals);
    }

    /**
     * 3. 根据连接ID和数据ID获取发送数据
     * @param connectId 连接ID（不可为null）
     * @param dataId    数据ID（不可为null）
     * @return 匹配的发送数据（null表示无匹配或映射关系异常）
     */
    public static SendQuicData getSendDataByConnectIdAndDataId(Long connectId, Long dataId) {
        // 空值校验
        if (connectId == null || dataId == null) {
            return null;
        }

        // 校验映射关系：确保该数据ID属于指定连接（防止数据ID被其他连接占用）
        Long mappedDataId = connectSendMap.get(connectId);
        if (dataId.equals(mappedDataId)) {
            return sendMap.get(dataId);
        }
        return null;
    }

    /**
     * 4. 根据连接ID和数据ID删除发送数据
     * @param connectId 连接ID（不可为null）
     * @param dataId    数据ID（不可为null）
     * @return true=删除成功，false=无匹配映射或参数异常
     */
    public static boolean deleteSendDataByConnectIdAndDataId(Long connectId, Long dataId) {
        // 空值校验
        if (connectId == null || dataId == null) {
            return false;
        }

        // 校验映射关系
        Long mappedDataId = connectSendMap.get(connectId);
        if (!dataId.equals(mappedDataId)) {
            return false;
        }

        // 删除数据缓存和映射关系
        SendQuicData remove = sendMap.remove(dataId);
        remove.release();
        connectSendMap.remove(connectId);
        return true;
    }

    // ====================== 接收数据操作方法 ======================
    /**
     * 5. 获取指定连接下的全部接收数据
     * @param connectId 连接ID（不可为null）
     * @return 该连接下所有有效的接收数据（不可变列表）
     */
    public static List<ReceiveQuicData> getAllReceiveDataByConnectId(Long connectId) {
        if (connectId == null) {
            return Collections.emptyList();
        }

        List<ReceiveQuicData> receiveDataList = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : connectReceiveMap.entrySet()) {
            if (connectId.equals(entry.getKey())) {
                Long dataId = entry.getValue();
                ReceiveQuicData receiveData = receiveMap.get(dataId);
                if (receiveData != null) {
                    receiveDataList.add(receiveData);
                }
            }
        }
        return Collections.unmodifiableList(receiveDataList);
    }

    /**
     * 6. 删除指定连接下的全部接收数据
     * @param connectId 连接ID（不可为null）
     */
    public static void deleteAllReceiveDataByConnectId(Long connectId) {
        if (connectId == null) {
            return;
        }

        // 收集数据ID
        List<Long> dataIds = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : connectReceiveMap.entrySet()) {
            if (connectId.equals(entry.getKey())) {
                dataIds.add(entry.getValue());
            }
        }

        // 删除数据缓存
        for (Long dataId : dataIds) {
            ReceiveQuicData remove = receiveMap.remove(dataId);
            remove.release();
        }

        // 删除映射关系
        connectReceiveMap.keySet().removeIf(connectId::equals);
    }

    /**
     * 7. 根据连接ID和数据ID获取接收数据
     * @param connectId 连接ID（不可为null）
     * @param dataId    数据ID（不可为null）
     * @return 匹配的接收数据（null表示无匹配或映射异常）
     */
    public static ReceiveQuicData getReceiveDataByConnectIdAndDataId(Long connectId, Long dataId) {
        if (connectId == null || dataId == null) {
            return null;
        }

        Long mappedDataId = connectReceiveMap.get(connectId);
        if (dataId.equals(mappedDataId)) {
            return receiveMap.get(dataId);
        }
        return null;
    }

    /**
     * 8. 根据连接ID和数据ID删除接收数据
     * @param connectId 连接ID（不可为null）
     * @param dataId    数据ID（不可为null）
     * @return true=删除成功，false=无匹配映射或参数异常
     */
    public static boolean deleteReceiveDataByConnectIdAndDataId(Long connectId, Long dataId) {
        if (connectId == null || dataId == null) {
            return false;
        }

        Long mappedDataId = connectReceiveMap.get(connectId);
        if (!dataId.equals(mappedDataId)) {
            return false;
        }

        ReceiveQuicData remove = receiveMap.remove(dataId);
        remove.release();
        connectReceiveMap.remove(connectId);
        return true;
    }


    /**
     * 判断指定连接下是否存在某一个发送数据
     * @param connectId 连接ID（不可为null）
     * @param dataId    数据ID（不可为null）
     * @return true=存在（映射关系有效且数据缓存存在），false=不存在或参数异常
     */
    public static boolean isSendDataExistInConnect(Long connectId, Long dataId) {
        // 空值校验
        if (connectId == null || dataId == null) {
            return false;
        }

        // 1. 校验连接与数据ID的映射关系
        Long mappedDataId = connectSendMap.get(connectId);
        if (!dataId.equals(mappedDataId)) {
            return false;
        }

        // 2. 校验数据缓存中是否存在该数据ID的有效数据
        return sendMap.containsKey(dataId) && sendMap.get(dataId) != null;
    }

    /**
     * 判断指定连接下是否存在某一个接收数据
     * @param connectId 连接ID（不可为null）
     * @param dataId    数据ID（不可为null）
     * @return true=存在（映射关系有效且数据缓存存在），false=不存在或参数异常
     */
    public static boolean isReceiveDataExistInConnect(Long connectId, Long dataId) {
        // 空值校验
        if (connectId == null || dataId == null) {
            return false;
        }

        // 1. 校验连接与数据ID的映射关系
        Long mappedDataId = connectReceiveMap.get(connectId);
        if (!dataId.equals(mappedDataId)) {
            return false;
        }

        // 2. 校验数据缓存中是否存在该数据ID的有效数据
        return receiveMap.containsKey(dataId) && receiveMap.get(dataId) != null;
    }






    /**
     * 添加连接下的发送数据（线程安全）
     * 1. 自动生成数据ID（基于雪花算法）
     * 2. 维护connectSendMap（连接ID->数据ID）和sendMap（数据ID->发送数据）的映射关系
     * 3. 覆盖已有映射时会先清理旧数据，避免内存泄漏
     *
     * @param connectId    连接ID（不可为null）
     * @param sendQuicData 发送数据实例（不可为null，需包含完整的发送元信息）
     * @return 成功添加返回true，参数异常/添加失败返回false
     * @throws IllegalArgumentException 参数为空时抛出
     */
    public static boolean addSendDataToConnect(Long connectId, SendQuicData sendQuicData) {
        // 1. 严格参数校验
        if (connectId == null) {
            throw new IllegalArgumentException("connectId cannot be null");
        }
        if (sendQuicData == null) {
            throw new IllegalArgumentException("sendQuicData cannot be null");
        }

        // 2. 自动生成数据ID（如果sendQuicData未设置）
        Long dataId = sendQuicData.getDataId();

        // 3. 线程安全处理：覆盖已有映射时先清理旧数据
        synchronized (connectSendMap) {
            // 3.1 检查该连接是否已有绑定的旧数据ID
            Long oldDataId = connectSendMap.get(connectId);
            if (oldDataId != null && !oldDataId.equals(dataId)) {
                // 清理旧数据缓存，避免内存泄漏
                SendQuicData remove = sendMap.remove(oldDataId);
                remove.release();
            }

            // 3.2 更新映射关系：连接ID->数据ID
            connectSendMap.put(connectId, dataId);

            // 3.3 更新数据缓存：数据ID->发送数据
            sendMap.put(dataId, sendQuicData);

            // 3.4 初始化发送数据的连接ID（确保关联正确）
            if (sendQuicData.getConnectionId() == 0) {
                sendQuicData.setConnectionId(connectId);
            }
        }

        return true;
    }

    /**
     * 添加连接下的接收数据（线程安全）
     * 1. 支持手动指定或自动生成数据ID
     * 2. 维护connectReceiveMap（连接ID->数据ID）和receiveMap（数据ID->接收数据）的映射关系
     * 3. 覆盖已有映射时会先清理旧数据，避免内存泄漏
     *
     * @param connectId       连接ID（不可为null）
     * @param receiveQuicData 接收数据实例（不可为null，需包含基础元信息）
     * @return 成功添加返回true，参数异常/添加失败返回false
     * @throws IllegalArgumentException 参数为空时抛出
     */
    public static boolean addReceiveDataToConnect(Long connectId, ReceiveQuicData receiveQuicData) {
        // 1. 严格参数校验
        if (connectId == null) {
            throw new IllegalArgumentException("connectId cannot be null");
        }
        if (receiveQuicData == null) {
            throw new IllegalArgumentException("receiveQuicData cannot be null");
        }

        // 2. 自动生成数据ID（如果receiveQuicData未设置）
        Long dataId = receiveQuicData.getDataId();

        // 3. 线程安全处理：覆盖已有映射时先清理旧数据
        synchronized (connectReceiveMap) {
            // 3.1 检查该连接是否已有绑定的旧数据ID
            Long oldDataId = connectReceiveMap.get(connectId);
            if (oldDataId != null && !oldDataId.equals(dataId)) {
                // 清理旧数据缓存，避免内存泄漏
                receiveMap.remove(oldDataId);
            }

            // 3.2 更新映射关系：连接ID->数据ID
            connectReceiveMap.put(connectId, dataId);

            // 3.3 更新数据缓存：数据ID->接收数据
            receiveMap.put(dataId, receiveQuicData);

            // 3.4 初始化接收数据的连接ID（确保关联正确）
            if (receiveQuicData.getConnectionId() == 0) {
                receiveQuicData.setConnectionId(connectId);
            }
        }

        return true;
    }



}
