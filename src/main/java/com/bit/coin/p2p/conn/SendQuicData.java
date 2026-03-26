package com.bit.coin.p2p.conn;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.bit.coin.p2p.conn.QuicConstants.MAX_RETRY_COUNT;
import static com.bit.coin.p2p.conn.QuicConstants.RETRY_INTERVAL_MS;

@Slf4j
@Data
public class SendQuicData extends QuicData{

    // 重传计数（默认0，最多重传1次）
    private AtomicInteger retryCount = new AtomicInteger(0);

    // 最后一次重传时间（ms），用于控制150ms间隔，初始为首次发送时间
    private AtomicLong lastRetryTime = new AtomicLong(0);


    //数据首次发送时间 用于超时重传判断
    private long sendTime;

    //超时时间 默认5秒 后期根据数据大小和网络平均时间来调整 此时触发未确认重传 超时重传机制
    private long timeout = 2500;

    //过期时间 要被删除
    private long expireTime = 5000;


    //是否发送完成
    private volatile boolean isCompleted = false;
    // ACK确认集合：记录B已确认的序列号
    private final Set<Integer> ackedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());
    //已经发送的序列号 通过已经发送和已经确认能确定哪些是丢失的
    private final Set<Integer> sentSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());


    //通过int[]来实现填充已经发送的帧序列号
    /**
     * 初始化已发送的帧序列号集合
     * @param sentSequences 本次发送的帧序列号数组（不能为空，元素为合法的帧序号，范围 0~totalFrames-1）
     */
    public void setSentSequenceArray(int[] sentSequences) {
        // 1. 入参合法性校验
        if (sentSequences == null || sentSequences.length == 0) {
            log.warn("[初始化已发送序列号] 入参为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return;
        }

        // 2. 校验序列号范围（防止非法序号）
        int totalFrames = getTotal();
        for (int sequence : sentSequences) {
            if (sequence < 0 || sequence >= totalFrames) {
                log.error("[初始化已发送序列号] 非法序列号{}，总帧数{}，连接ID:{} 数据ID:{}",
                        sequence, totalFrames, getConnectionId(), getDataId());
                return; // 非法序号直接终止，避免脏数据
            }
        }

        // 3. 批量添加到已发送集合（Set自动去重，无需额外处理重复序号）
        int addedCount = 0;
        for (int sequence : sentSequences) {
            if (this.sentSequences.add(sequence)) {
                addedCount++;
            }
        }

        // 4. 日志记录（保持与类中其他方法日志风格一致）
        log.info("[初始化已发送序列号] 连接ID:{} 数据ID:{} 总帧数:{} 传入序列号数:{} 实际新增数:{}",
                getConnectionId(), getDataId(), totalFrames, sentSequences.length, addedCount);
    }


    /**
     * 批量获取帧（优先获取已发送但未ACK且超时的重传帧，再补充未发送帧，总字节数≤size）
     * @param size 最大字节数限制（正数，若≤0返回空数组）
     * @return 帧数组（优先重传帧，后未发送帧），无符合条件的帧时返回空数组
     */
    /**
     * 批量获取帧（优先获取已发送但未ACK且超时的重传帧，再补充未发送帧，总字节数≤size）
     * @param size 最大字节数限制（正数，若≤0返回空数组）
     * @return 帧数组（优先重传帧，后未发送帧），无符合条件的帧时返回空数组
     */
    public QuicFrame[] getUnsentFrameAndTimeOut(int size) {
        // 1. 基础参数与状态校验（原有逻辑不变）
        if (size <= 0) {
            log.warn("[优先重传-获取帧] 字节数限制非法（size={}），连接ID:{} 数据ID:{}",
                    size, getConnectionId(), getDataId());
            return new QuicFrame[0];
        }
        if (isCompleted) {
            log.debug("[优先重传-获取帧] 数据传输已完成，无待处理帧，连接ID:{} 数据ID:{}",
                    getConnectionId(), getDataId());
            return new QuicFrame[0];
        }
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - sendTime;
        if (timeDiff > expireTime) {
            log.info("[优先重传-获取帧] 数据已过期（超时{}ms），无待处理帧，连接ID:{} 数据ID:{}",
                    timeDiff, getConnectionId(), getDataId());
            return new QuicFrame[0];
        }
        int totalFrames = getTotal();
        if (totalFrames <= 0) {
            log.warn("[优先重传-获取帧] 总帧数为0，无待处理帧，连接ID:{} 数据ID:{}",
                    getConnectionId(), getDataId());
            return new QuicFrame[0];
        }

        // 2. 第一步：筛选「已发送但未ACK且超时」的序列号（重传优先级最高）
        List<Integer> timeoutUnAckSequenceList = new ArrayList<>();
        boolean isTimeout = timeDiff > timeout;
        if (isTimeout) {
            for (int sequence = 0; sequence < totalFrames; sequence++) {
                if (sentSequences.contains(sequence) && !ackedSequences.contains(sequence)) {
                    timeoutUnAckSequenceList.add(sequence);
                }
            }
            log.debug("[优先重传-获取帧] 超时待重传序列号数:{}，连接ID:{} 数据ID:{}",
                    timeoutUnAckSequenceList.size(), getConnectionId(), getDataId());
        }

        // 3. 按字节数筛选重传帧（核心修改：给重传帧打标记）
        List<QuicFrame> resultFrameList = new ArrayList<>();
        int currentTotalSize = 0;
        // 3.1 处理重传帧：标记isRetransmit=true
        for (int sequence : timeoutUnAckSequenceList) {
            QuicFrame frame = getFrameBySequence(sequence);
            if (frame == null) {
                log.error("[优先重传-获取帧] 重传帧序列号{}为空，跳过，连接ID:{} 数据ID:{}",
                        sequence, getConnectionId(), getDataId());
                continue;
            }
            // 关键：标记为重传帧
            frame.setRetransmit(true);
            int frameSize = frame.getSize();
            if (currentTotalSize + frameSize > size && !resultFrameList.isEmpty()) {
                break;
            }
            resultFrameList.add(frame);
            currentTotalSize += frameSize;
            if (currentTotalSize >= size) {
                break;
            }
        }

        // 4. 第二步：补充未发送的帧（核心修改：给首次帧打标记）
        if (currentTotalSize < size) {
            List<Integer> unsentSequenceList = new ArrayList<>();
            for (int sequence = 0; sequence < totalFrames; sequence++) {
                if (!sentSequences.contains(sequence)) {
                    unsentSequenceList.add(sequence);
                }
            }
            // 补充未发送帧：标记isRetransmit=false
            for (int sequence : unsentSequenceList) {
                QuicFrame frame = getFrameBySequence(sequence);
                if (frame == null) {
                    log.error("[优先重传-获取帧] 未发送帧序列号{}为空，跳过，连接ID:{} 数据ID:{}",
                            sequence, getConnectionId(), getDataId());
                    continue;
                }
                // 关键：标记为首次发送帧
                frame.setRetransmit(false);
                int frameSize = frame.getSize();
                if (currentTotalSize + frameSize > size && !resultFrameList.isEmpty()) {
                    break;
                }
                resultFrameList.add(frame);
                currentTotalSize += frameSize;
                if (currentTotalSize >= size) {
                    break;
                }
            }
        }

        // 5. 转换为数组并记录日志（原有逻辑不变）
        QuicFrame[] result = resultFrameList.toArray(new QuicFrame[0]);
        int retryFrameCount = Math.min(timeoutUnAckSequenceList.size(), resultFrameList.size());
        int unsentFrameCount = resultFrameList.size() - retryFrameCount;
        log.debug("[优先重传-获取帧] 连接ID:{} 数据ID:{} 字节数限制:{} 实际总字节数:{} 重传帧数:{} 未发送帧数:{}",
                getConnectionId(), getDataId(), size, currentTotalSize, retryFrameCount, unsentFrameCount);
        return result;
    }


    /**
     * 按字节大小批量获取未发送的帧（按序列号升序，总字节数≤size）
     * @param size 最大字节数限制（正数，若≤0返回空数组）
     * @return 未发送的QuicFrame数组，无符合条件的帧时返回空数组
     */
    public QuicFrame[] getUnsentFrame(int size) {
        // 1. 基础参数与状态校验（原有逻辑完全不变）
        if (size <= 0) {
            log.warn("[批量获取未发送帧] 字节数限制非法（size={}），连接ID:{} 数据ID:{}",
                    size, getConnectionId(), getDataId());
            return new QuicFrame[0];
        }
        if (isCompleted) {
            log.debug("[批量获取未发送帧] 数据传输已完成，无未发送帧，连接ID:{} 数据ID:{}",
                    getConnectionId(), getDataId());
            return new QuicFrame[0];
        }
        long timeDiff = System.currentTimeMillis() - sendTime;
        if (timeDiff > expireTime) {
            log.info("[批量获取未发送帧] 数据已过期（超时{}ms），无未发送帧，连接ID:{} 数据ID:{}",
                    timeDiff, getConnectionId(), getDataId());
            return new QuicFrame[0];
        }
        int totalFrames = getTotal();
        if (totalFrames <= 0) {
            log.warn("[批量获取未发送帧] 总帧数为0，无未发送帧，连接ID:{} 数据ID:{}",
                    getConnectionId(), getDataId());
            return new QuicFrame[0];
        }

        // 2. 收集所有未发送的序列号（按升序，原有逻辑不变）
        List<Integer> unsentSequenceList = new ArrayList<>();
        for (int sequence = 0; sequence < totalFrames; sequence++) {
            if (!sentSequences.contains(sequence)) {
                unsentSequenceList.add(sequence);
            }
        }
        if (unsentSequenceList.isEmpty()) {
            log.debug("[批量获取未发送帧] 所有帧已发送（总帧数:{}），无未发送帧，连接ID:{} 数据ID:{}",
                    totalFrames, getConnectionId(), getDataId());
            return new QuicFrame[0];
        }

        // 3. 按字节数筛选未发送帧（核心修改：给首次帧打标记）
        List<QuicFrame> unsentFrameList = new ArrayList<>();
        int currentTotalSize = 0;
        for (int sequence : unsentSequenceList) {
            QuicFrame frame = getFrameBySequence(sequence);
            if (frame == null) {
                log.error("[批量获取未发送帧] 序列号{}对应帧为空，跳过该帧，连接ID:{} 数据ID:{}",
                        sequence, getConnectionId(), getDataId());
                continue;
            }
            // ========== 唯一修改：给首次发送帧标记isRetransmit=false ==========
            frame.setRetransmit(false);
            // ===============================================================
            int frameSize = frame.getSize();
            if (currentTotalSize + frameSize > size && !unsentFrameList.isEmpty()) {
                break;
            }
            unsentFrameList.add(frame);
            currentTotalSize += frameSize;
            if (currentTotalSize >= size) {
                break;
            }
        }

        // 4. 转换为数组并记录日志（原有逻辑不变）
        QuicFrame[] result = unsentFrameList.toArray(new QuicFrame[0]);
        log.debug("[批量获取未发送帧] 连接ID:{} 数据ID:{} 字节数限制:{} 实际获取帧数:{} 实际总字节数:{} 剩余未发送帧数:{}",
                getConnectionId(), getDataId(), size, result.length, currentTotalSize,
                unsentSequenceList.size() - result.length);
        return result;
    }



    public int[] getUnAckedSequences() {
        //是否完成
        if (isCompleted) {
            return new int[0];
        }
        //是否过期 当前时间 和 发送时间差值
        long timeDiff = System.currentTimeMillis() - sendTime;
        if (timeDiff > expireTime) {
            log.info("[数据过期] 已超过过期时间，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return new int[0];
        }

        // 先获取总帧数，避免空指针或非法值
        int totalFrames = getTotal();
        if (totalFrames <= 0) {
            log.info("[获取未确认序列号] 总帧数为0，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return new int[0];
        }

        // 收集未确认的序列号
        List<Integer> unAckedList = new ArrayList<>();
        for (int sequence = 0; sequence < totalFrames; sequence++) {
            // 不在已确认集合中，即为未确认
            if (!ackedSequences.contains(sequence)) {
                unAckedList.add(sequence);
            }
        }

        // 转换为数组返回（保持有序，便于重传逻辑处理）
        int[] unAckedArray = new int[unAckedList.size()];
        for (int i = 0; i < unAckedList.size(); i++) {
            unAckedArray[i] = unAckedList.get(i);
        }

        log.debug("[获取未确认序列号] 连接ID:{} 数据ID:{} 总帧数:{} 未确认数量:{}",
                getConnectionId(), getDataId(), totalFrames, unAckedArray.length);
        return unAckedArray;
    }




    /**
     * 确认一个序列号
     * @param sequence 需要添加到确认序列好集合中
     * @return 是否全部确认
     */
    public boolean onAckReceived(int sequence) {
        // 仅处理未确认的序列号
        if (ackedSequences.add(sequence)) {
            log.debug("[ACK处理] 连接ID:{} 数据ID:{} 序列号:{} 已确认，取消重传定时器",
                    getConnectionId(), getDataId(), sequence);
            // 检查是否所有帧都已确认
            if (ackedSequences.size() == getTotal()) {
                log.info("[所有帧确认] 连接ID:{} 数据ID:{} 传输完成", getConnectionId(), getDataId());
                handleSendSuccess();
                return true;
            }
        }
        return false;
    }

    /**
     * 处理批量ACK：解析字节数组中的比特位，标记对应序列号为已确认
     * 格式约定：每个比特代表一个序列号的确认状态（1=已确认，0=未确认），采用大端序（第0位对应序列号0）
     * payload 结构: [比特位图(总帧数+7)/8字节][接收窗口4字节]
     * @param ackData 批量ACK的比特位数组（长度为 (总帧数+7)/8 向上取整 + 4字节接收窗口）
     * @return 返回接收窗口大小及确认帧数等信息
     */
    synchronized public AckResult handleBitMapACKFrame(byte[] ackData) {
        long rtime = System.nanoTime();
        if (ackData == null || ackData.length == 0) {
            log.warn("[批量ACK处理] ACK列表为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            // 空数据时确认帧数为0
            return new AckResult(false, false, 0, 0);
        }

        int totalFrames = getTotal();
        int expectedByteLength = (totalFrames + 7) / 8 + 4; // 计算预期的字节长度（比特位图 + 4字节接收窗口）

        // 校验ACK列表长度是否匹配总帧数（防止恶意或错误的ACK数据）
        if (ackData.length < expectedByteLength) {
            log.warn("[批量ACK处理] ACK列表长度不足，总帧数:{} 预期最小字节数:{} 实际字节数:{}，连接ID:{} 数据ID:{}",
                    totalFrames, expectedByteLength, ackData.length, getConnectionId(), getDataId());
            // 长度不匹配时确认帧数为0
            return new AckResult(false, false, 0, 0);
        }

        int confirmedCount = 0; // 统计本次批量确认的新序列号数量
        int bitmapLength = (totalFrames + 7) / 8; // 比特位图的实际长度

        // 解析接收窗口（payload 最后 4 字节）
        int receiveWindow = 0;
        if (ackData.length >= bitmapLength + 4) {
            // 按大端序解析 int（4字节）
            receiveWindow = ((ackData[bitmapLength] & 0xFF) << 24) |
                    ((ackData[bitmapLength + 1] & 0xFF) << 16) |
                    ((ackData[bitmapLength + 2] & 0xFF) << 8) |
                    (ackData[bitmapLength + 3] & 0xFF);
            log.debug("[解析接收窗口] 连接ID:{} 数据ID:{} 接收窗口:{}字节",
                    getConnectionId(), getDataId(), receiveWindow);
        }

        // 遍历每个字节解析比特位（只解析比特位图部分）
        for (int byteIndex = 0; byteIndex < bitmapLength; byteIndex++) {
            byte ackByte = ackData[byteIndex];

            // 遍历当前字节的8个比特（大端序：bitIndex=0对应最高位，代表序列号 byteIndex*8 + 0）
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                int sequence = byteIndex * 8 + bitIndex; // 计算对应的序列号

                // 序列号超出总帧数范围时终止（最后一个字节可能有无效比特）
                if (sequence >= totalFrames) {
                    break;
                }
                // 检查当前比特是否为1（已确认）
                boolean isAcked = (ackByte & (1 << (7 - bitIndex))) != 0;
                if (isAcked) {
                    // 调用已有的单ACK处理逻辑（自动去重并检查是否全部确认）
                    if (ackedSequences.add(sequence)) {
                        // 帧接收时间
                        confirmedCount++;
                        log.debug("[批量ACK确认] 连接ID:{} 数据ID:{} 序列号:{} 已确认",
                                getConnectionId(), getDataId(), sequence);
                    }
                }
            }
        }

        log.debug("[批量ACK处理完成] 连接ID:{} 数据ID:{} 总帧数:{} 本次确认新序列号:{} 累计确认:{}",
                getConnectionId(), getDataId(), totalFrames, confirmedCount, ackedSequences.size());

        // 检查是否所有帧都已确认（触发完成逻辑）
        if (ackedSequences.size() == totalFrames) {
            log.debug("[所有帧通过批量ACK确认] 连接ID:{} 数据ID:{} 传输完成", getConnectionId(), getDataId());
            handleSendSuccess();
            // 关键修改：将confirmedCount传入AckResult的ackedFrameCounts字段
            return new AckResult(true, true, receiveWindow, confirmedCount);
        }
        // 关键修改：将confirmedCount传入AckResult的ackedFrameCounts字段
        return new AckResult(true, false, receiveWindow, confirmedCount);
    }



    private void handleSendSuccess() {
        isCompleted = true;
    }


    public int getRetryCount() {
        return retryCount.get();
    }

    // 重传计数+1
    public void incrementRetryCount() {
        retryCount.incrementAndGet();
    }


    //超时
    /**
     * 修正：判断是否超时（修复nanoTime与ms混用问题）
     * @return true=已超时（首次发送后超过timeout未收到ACK）
     */
    public boolean isTimeout() {
        if (sendTime <= 0) {
            return false;
        }
        long currentTime = System.currentTimeMillis();
        return (currentTime - sendTime) > timeout;
    }


    //过期
    /**
     * 修正：判断是否过期（修复nanoTime与ms混用问题）
     * @return true=已过期（超过expireTime，直接丢弃）
     */
    public boolean isExpired() {
        if (sendTime <= 0) {
            return false;
        }
        long currentTime = System.currentTimeMillis();
        return (currentTime - sendTime) > expireTime;
    }


    //超时且到了重传次数了 每150ms重传一次
    /**
     * 核心方法：判断是否需要重传（满足所有条件才返回true）
     * 重传条件：
     * 1. 数据未完成传输（!isCompleted）
     * 2. 数据未过期（!isExpired）
     * 3. 已超时（isTimeout）
     * 4. 重传次数未达上限（retryCount < MAX_RETRY_COUNT）
     * 5. 距离上次重传已过150ms（首次重传无间隔限制）
     * @return true=需要重传，false=无需重传
     */
    public boolean isNeedRetryWithInterval() {
        // 1. 基础状态校验：已完成/已过期 → 直接返回false
        if (isCompleted) {
            log.debug("[重传判断] 数据已完成传输，无需重传，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return false;
        }
        if (isExpired()) {
            log.warn("[重传判断] 数据已过期（超时{}ms），终止重传，连接ID:{} 数据ID:{}",
                    System.currentTimeMillis() - sendTime, getConnectionId(), getDataId());
            return false;
        }

        // 2. 重传次数校验：已达上限 → 终止重传
        int currentRetryCount = retryCount.get();
        if (currentRetryCount >= MAX_RETRY_COUNT) {
            log.warn("[重传判断] 重传次数已达上限（{}次），终止重传，连接ID:{} 数据ID:{}",
                    MAX_RETRY_COUNT, getConnectionId(), getDataId());
            return false;
        }

        // 3. 超时校验：未超时 → 无需重传
        if (!isTimeout()) {
            long remainingTime = timeout - (System.currentTimeMillis() - sendTime);
            log.debug("[重传判断] 数据未超时（剩余{}ms），无需重传，连接ID:{} 数据ID:{}",
                    remainingTime, getConnectionId(), getDataId());
            return false;
        }

        // 4. 重传间隔校验：确保两次重传间隔≥150ms
        long currentTime = System.currentTimeMillis();
        long lastRetry = lastRetryTime.get();
        // 首次重传（lastRetry=0）无间隔限制；非首次需满足间隔≥150ms
        if (lastRetry > 0 && (currentTime - lastRetry) < RETRY_INTERVAL_MS) {
            long remainingInterval = RETRY_INTERVAL_MS - (currentTime - lastRetry);
            log.debug("[重传判断] 重传间隔未到（剩余{}ms），暂不重传，连接ID:{} 数据ID:{}",
                    remainingInterval, getConnectionId(), getDataId());
            return false;
        }

        // 5. 所有条件满足，标记最后重传时间，返回需要重传
        lastRetryTime.set(currentTime);
        log.info("[重传判断] 满足重传条件（重传次数:{}/{}，间隔:{}ms），准备重传，连接ID:{} 数据ID:{}",
                currentRetryCount + 1, MAX_RETRY_COUNT, RETRY_INTERVAL_MS, getConnectionId(), getDataId());
        return true;
    }


    /**
     * 处理批量ACK（最多10帧）：更新已确认序列号，统计确认数并返回结果
     * @param sequences 接收方确认的序列号数组（最多10个，超出则截断并告警）
     * @return AckResult：解析结果（是否成功、是否传输完成、接收窗口（暂0）、本次确认帧数）
     * 备注：接收窗口需结合实际场景补充（如从其他参数传入），当前默认返回0
     */
    public AckResult handleBatchACKFrame(int[] sequences) {
        // 1. 入参基础校验
        if (sequences == null || sequences.length == 0) {
            log.warn("[批量ACK处理] 确认序列号数组为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return new AckResult(false, false, 0, 0);
        }

        // 2. 序列号数量校验（最多10个，超出则截断并告警）
        int maxBatchSize = 10;
        if (sequences.length > maxBatchSize) {
            log.warn("[批量ACK处理] 确认序列号数量超出上限（上限{}，实际{}），自动截断，连接ID:{} 数据ID:{}",
                    maxBatchSize, sequences.length, getConnectionId(), getDataId());
        }

        // 3. 总帧数校验（无有效总帧数则直接返回）
        int totalFrames = getTotal();
        if (totalFrames <= 0) {
            log.warn("[批量ACK处理] 总帧数非法（{}），无法处理确认，连接ID:{} 数据ID:{}",
                    totalFrames, getConnectionId(), getDataId());
            return new AckResult(false, false, 0, 0);
        }

        // 4. 遍历序列号，更新确认状态并统计本次新确认数
        int confirmedCount = 0; // 本次新确认的序列号数
        for (int sequence : sequences) {
            // 跳过超出范围的非法序列号
            if (sequence < 0 || sequence >= totalFrames) {
                log.warn("[批量ACK处理] 非法序列号{}（总帧数{}），跳过，连接ID:{} 数据ID:{}",
                        sequence, totalFrames, getConnectionId(), getDataId());
                continue;
            }

            // Set自动去重，仅统计「新增」的确认数
            if (ackedSequences.add(sequence)) {
                confirmedCount++;
                log.debug("[批量ACK确认] 连接ID:{} 数据ID:{} 序列号:{} 已确认",
                        getConnectionId(), getDataId(), sequence);
            }
        }

        // 5. 检查是否所有帧都已确认（触发传输完成逻辑）
        boolean isAllAcked = ackedSequences.size() == totalFrames;
        if (isAllAcked) {
            log.info("[批量ACK处理] 所有帧已确认（总帧数{}），连接ID:{} 数据ID:{} 传输完成",
                    totalFrames, getConnectionId(), getDataId());
            handleSendSuccess();
        }

        // 6. 日志汇总 + 返回标准化结果
        log.debug("[批量ACK处理完成] 连接ID:{} 数据ID:{} 总帧数:{} 本次确认新序列号数:{} 累计确认数:{}",
                getConnectionId(), getDataId(), totalFrames, confirmedCount, ackedSequences.size());

        // 接收窗口：当前参数未传入，暂返回0（可根据实际场景扩展，如新增window参数）
        return new AckResult(true, isAllAcked, 0, confirmedCount);
    }
}
