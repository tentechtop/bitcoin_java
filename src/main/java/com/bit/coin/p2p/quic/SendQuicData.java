package com.bit.coin.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.coin.p2p.quic.QuicConnectionManager.Global_Channel;
import static com.bit.coin.p2p.quic.QuicConnectionManager.getConnection;
import static com.bit.coin.p2p.quic.QuicConstants.*;


@Slf4j
@Data
public class SendQuicData extends QuicData {

    //整体超时
    public  final long GLOBAL_TIMEOUT_MS = 5000;

    // 帧重传间隔 应该计算无错误发送一次数据完整用时
    public  final long RETRANSMIT_INTERVAL_MS = 20;

    // 新增：首次重传扫描延迟时间（30ms）
    public  final long FIRST_RETRANSMIT_DELAY_MS = 500;

    // ACK确认集合：记录B已确认的序列号
    private final  Set<Integer> ackedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Timeout globalTimeout = null;

    // 新增：重传定时器（每个SendQuicData实例独立）
    private Timeout retransmitTimeout = null;

    // 新增：重传循环索引（0-9循环，用于匹配序列号结尾数字）
    private int cycleRetransmitIndex = 0;

    // 传输完成回调
    private Runnable successCallback;
    // 传输失败回调
    private Runnable failCallback;

    //是否失败
    private volatile boolean isFailed = false;

    //是否已经完成
    private volatile boolean isCompleted = false;

    //发送时间 用来计算 平均每帧所需要的时间
    private long sendTime;

    //完成时间
    private long completeTime;

    //总共通过UDP发送多少帧
    private AtomicInteger totalSendCount = new AtomicInteger(0);

    //重传轮数
    private int retransmitCount = 0;







    /**
     * 构建发送数据
     * @param connectionId
     * @param dataId
     * @param sendData 需要发送的数据
     * @param maxFrameSize 每帧最多maxFrameSize字节
     * @return
     */
    public static SendQuicData buildFromFullData(long connectionId, long dataId,
                                             byte[] sendData,InetSocketAddress remoteAddress, int maxFrameSize) {
        // 前置校验
        if (sendData == null) {
            throw new IllegalArgumentException("数据不能为空");
        }
        if (maxFrameSize <= 0) {
            throw new IllegalArgumentException("单帧最大帧载荷长度必须大于0，当前值: " + maxFrameSize);
        }
        if (remoteAddress == null) {
            throw new IllegalArgumentException("目标地址不能为空");
        }

        SendQuicData quicData = new SendQuicData();
        quicData.setConnectionId(connectionId);
        quicData.setDataId(dataId);

        int fullDataLength = sendData.length;
        int totalFrames = (fullDataLength + maxFrameSize - 1) / maxFrameSize;
        //如果总帧数大于MAX_FRAME 直接报错
        if (totalFrames > MAX_FRAME) {
            log.info("当前帧数量{} ",totalFrames);
            throw new IllegalArgumentException("帧数量超出最大限制");
        }

        // 计算分片总数：向上取整（避免因整数除法丢失最后一个不完整分片）
        quicData.setTotal(totalFrames);//分片总数
        quicData.setFrameArray(new QuicFrame[totalFrames]);
        // 2. 分片构建QuicFrame
        try {
            for (int sequence = 0; sequence < totalFrames; sequence++) {
                // 创建帧实例
                QuicFrame frame =  QuicFrame.acquire();
                frame.setConnectionId(connectionId);
                frame.setDataId(dataId);
                frame.setTotal(totalFrames);
                frame.setFrameType(QuicFrameEnum.DATA_FRAME.getCode());
                frame.setSequence(sequence);
                frame.setRemoteAddress(remoteAddress);

                int startIndex = sequence * maxFrameSize;
                int endIndex = Math.min(startIndex + maxFrameSize, fullDataLength);
                int currentPayloadLength = endIndex - startIndex;

                // 截取载荷数据（从原始数据复制对应区间）
                byte[] payload = new byte[currentPayloadLength];
                System.arraycopy(sendData, startIndex, payload, 0, currentPayloadLength);
                // 设置帧的总长度（固定头部 + 实际载荷长度）
                frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + currentPayloadLength);
                frame.setPayload(payload);
                quicData.getFrameArray()[sequence] = frame;
                log.debug("构建分片完成: connectionId={}, dataId={}, 序列号={}, 载荷长度={}, 总长度={}",
                        connectionId, dataId, sequence, currentPayloadLength, frame.getFrameTotalLength());
            }
            quicData.setSize(sendData.length);
        } catch (Exception e) {
            log.error("构建QuicData失败 connectionId={}, dataId={}", connectionId, dataId, e);
            // 异常时释放已创建的帧
            throw new RuntimeException("构建QuicData失败", e);
        }
        return quicData;
    }


    synchronized public void sendAllFrames() throws InterruptedException {
        if (getFrameArray() == null || getFrameArray().length == 0) {
            log.error("[发送失败] 帧数组为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
            return;
        }
        log.debug("[开始发送] 连接ID:{} 数据ID:{} 总帧数:{}", getConnectionId(), getDataId(), getTotal());
        startGlobalTimeoutTimer();
        for (int sequence = 0; sequence < getTotal(); sequence++) {
            QuicFrame frame = getFrameArray()[sequence];
            if (frame != null && !isFailed()) {
                sendFrame(frame);
            }
        }
        setSendTime(System.nanoTime());
        if (!isCompleted && !isFailed()){
            startRetransmitTimer();
        }
    }

    /**
     * 发送单个帧
     */
    synchronized private void sendFrame(QuicFrame frame) {
        if (!isFailed() && frame!=null && !ackedSequences.contains(frame.getSequence())  && !isCompleted()){
            int sequence = frame.getSequence();
            ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
            try {
                QuicConnection connection = getConnection(getConnectionId());
                if (getConnectionId()!=0 && connection!=null){
                    frame.encode(buf);
                    DatagramPacket packet = new DatagramPacket(buf, frame.getRemoteAddress());
                    Global_Channel.writeAndFlush(packet).addListener(future -> {
                        // 核心：释放 ByteBuf（无论发送成功/失败）
                        if (!future.isSuccess()) {
                            log.error("[帧发送失败] 连接ID:{} 数据ID:{} 序列号:{}",
                                    getConnectionId(), getDataId(), sequence, future.cause());
                        } else {
                            log.debug("[帧发送成功] 连接ID:{} 数据ID:{} 序列号:{}",
                                    getConnectionId(), getDataId(), sequence);
                            totalSendCount.incrementAndGet();
                            frame.setTime(System.nanoTime());
                        }
                    });
                }
            } catch (Exception e) {
                // 异常时直接释放 buf，避免泄漏
                log.error("[帧编码失败] 连接ID:{} 数据ID:{} 序列号:{}",
                        getConnectionId(), getDataId(), sequence, e);
            }
     /*       Thread.ofVirtual()
                    .name("send-virtual-thread")
                    .start(() -> {

                    });*/
        }
    }


    /**
     * 启动全局超时定时器
     */
    private void startGlobalTimeoutTimer() {
        if (globalTimeout == null) {
            globalTimeout = GLOBAL_TIMER.newTimeout(new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    if (timeout.isCancelled()) {
                        return;
                    }
                    log.info("[全局超时] 连接ID:{} 数据ID:{} 在{}ms内未完成发送，发送失败",
                            getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
                    handleSendFailure();
                }
            }, GLOBAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            log.debug("[全局定时器启动] 连接ID:{} 数据ID:{} 超时时间:{}ms",
                    getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
        }
    }



    /**
     * 新增：启动重传定时器（周期性检查未ACK帧并重传）
     */
    private void startRetransmitTimer() {
        if (retransmitTimeout == null && !isCompleted() && !isFailed()) {
            // 修改点1：首次重传延迟使用FIRST_RETRANSMIT_DELAY_MS（30ms）
            retransmitTimeout = GLOBAL_TIMER.newTimeout(new RetransmitTask(), FIRST_RETRANSMIT_DELAY_MS, TimeUnit.MILLISECONDS);
            log.debug("[重传定时器启动] 连接ID:{} 数据ID:{} 首次重传延迟:{}ms 后续重传间隔:{}ms",
                    getConnectionId(), getDataId(), FIRST_RETRANSMIT_DELAY_MS, RETRANSMIT_INTERVAL_MS);
        }
    }

    /**
     * 新增：重传任务（核心逻辑：筛选未ACK帧并重发，同时实现周期性执行）
     */
    private class RetransmitTask implements TimerTask {
        @Override
        public void run(Timeout timeout) throws Exception {
            // 1. 先判断当前任务是否被取消，或传输已完成/失败，若已终止则不再继续
            QuicConnection connection = getConnection(getConnectionId());
            if (timeout.isCancelled()
                    || (globalTimeout != null && globalTimeout.isCancelled())
                    || ackedSequences.size() == getTotal()
                    || connection==null
            ) {
                log.debug("[重传任务终止] 连接ID:{} 数据ID:{}（任务已取消/传输完成）",
                        getConnectionId(), getDataId());
                retransmitTimeout = null;
                return;
            }

            // 2. 筛选未收到ACK的帧并重传
            int retransmitCount = 0;
            QuicFrame[] frameArray = getFrameArray();
            if (frameArray == null) {
                log.warn("[重传失败] 帧数组为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
                // 重新注册下一次重传任务
                reRegisterRetransmitTask();
                return;
            }
            for (int sequence = 0; sequence < frameArray.length; sequence++) {
                // 跳过已ACK的帧
                if (ackedSequences.contains(sequence)) {
                    continue;
                }

                // 核心修改：只重传 序列号%10 == 循环索引（即序列号结尾数字匹配）的帧
                if (sequence % 10 != cycleRetransmitIndex) {
                    continue;
                }

                QuicFrame frame = frameArray[sequence];
                if (frame != null) {
                    if (!isFailed()) {
                        // 重传未ACK帧
                        sendFrame(frame);
                        retransmitCount++;
                        log.debug("[触发重传] 连接ID:{} 数据ID:{} 序列号:{}",
                                getConnectionId(), getDataId(), sequence);
                        if (retransmitCount == 128) {
                            break; // 跳出for循环，终止本次重传遍历
                        }
                    } else {
                        log.warn("[重传自旋超时/失败] 连接ID:{} 数据ID:{} 序列号:{} 50ms内无法获取发送权限",
                                getConnectionId(), getDataId(), sequence);
                    }
                }
            }
            log.info("[重传检查完成] 连接ID:{} 数据ID:{} 本次重传帧数:{} 累计已ACK:{} 总帧数:{}",
                    getConnectionId(), getDataId(), retransmitCount, ackedSequences.size(), getTotal());
            // 3. 更新循环索引：0-9循环（执行完本次重传后更新，下次重传匹配下一个结尾数字）
            cycleRetransmitIndex = (cycleRetransmitIndex + 1) % 10;

            // 3. 重新注册下一次重传任务（实现周期性执行）
            reRegisterRetransmitTask();
        }
    }

    /**
     * 新增：重新注册重传任务（保证每300ms执行一次）
     */
    private void reRegisterRetransmitTask() {
        synchronized (this) {
            // 只有当重传定时器未被取消、传输未完成时，才重新注册
            if (retransmitTimeout != null
                    && !retransmitTimeout.isCancelled()
                    && ackedSequences.size() < getTotal()
                    && (globalTimeout == null || !globalTimeout.isCancelled())
                    && !isCompleted
                    && !isFailed
            ) {
                retransmitTimeout = GLOBAL_TIMER.newTimeout(new RetransmitTask(), RETRANSMIT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } else {
                retransmitTimeout = null;
            }
        }
    }



    /**
     * 处理发送失败
     */
    private void handleSendFailure() {
        log.info("[处理发送失败] 连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
        // 从发送Map中移除
        deleteSendDataByConnectIdAndDataId(getConnectionId(), getDataId());
        // 取消所有定时器
        cancelAllTimers();
        // 释放帧资源
        // 执行失败回调
        if (failCallback != null) {
            failCallback.run();
        }
        isFailed = true;
    }

    /**
     * 取消所有定时器
     */
    private void cancelAllTimers() {
        // 取消全局定时器
        if (globalTimeout != null) {
            globalTimeout.cancel();
            globalTimeout = null;
        }
        // 取消重传定时器
        if (retransmitTimeout != null) {
            retransmitTimeout.cancel();
            retransmitTimeout = null;
        }
    }




    public void onAckReceived(int sequence) {
        // 仅处理未确认的序列号
        if (ackedSequences.add(sequence)) {
            log.debug("[ACK处理] 连接ID:{} 数据ID:{} 序列号:{} 已确认，取消重传定时器",
                    getConnectionId(), getDataId(), sequence);
            // 检查是否所有帧都已确认
            if (ackedSequences.size() == getTotal()) {
                log.info("[所有帧确认] 连接ID:{} 数据ID:{} 传输完成", getConnectionId(), getDataId());
                handleSendSuccess();
            }
        }
    }


    /**
     * 处理发送成功（所有帧均被确认）
     */
    private void handleSendSuccess() {
        log.info("数据发送完毕");
        long end = System.nanoTime();
        setCompleteTime(end);
        setCompleted(true);
        // 取消全局超时定时器
        if (globalTimeout != null) {
            globalTimeout.cancel();
            globalTimeout = null;
        }
        // 清理资源
        cancelAllTimers();
        //释放掉所有的帧
        release();
        // 从发送缓存中移除
        deleteSendDataByConnectIdAndDataId(getConnectionId(), getDataId());
        // 执行成功回调
        if (successCallback != null) {
            successCallback.run();
        }
    }


    //立即释放全部的资源
    public void allReceived() {
        handleSendSuccess();
    }

    /**
     * 处理批量ACK：解析字节数组中的比特位，标记对应序列号为已确认
     * 格式约定：每个比特代表一个序列号的确认状态（1=已确认，0=未确认），采用大端序（第0位对应序列号0）
     * @param ackList 批量ACK的比特位数组（长度为 (总帧数+7)/8 向上取整）
     */
    synchronized public void batchAck(byte[] ackList) {
        long rtime = System.nanoTime();
        QuicConnection connection = getConnection(getConnectionId());
        if (!isCompleted() && !isFailed() && connection!=null){

            if (ackList == null || ackList.length == 0) {
                log.warn("[批量ACK处理] ACK列表为空，连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
                return;
            }

            int totalFrames = getTotal();
            int expectedByteLength = (totalFrames + 7) / 8; // 计算预期的字节长度

            // 校验ACK列表长度是否匹配总帧数（防止恶意或错误的ACK数据）
            if (ackList.length != expectedByteLength) {
                log.warn("[批量ACK处理] ACK列表长度不匹配，总帧数:{} 预期字节数:{} 实际字节数:{}，连接ID:{} 数据ID:{}",
                        totalFrames, expectedByteLength, ackList.length, getConnectionId(), getDataId());
                return;
            }

            int confirmedCount = 0; // 统计本次批量确认的新序列号数量

            // 遍历每个字节解析比特位
            for (int byteIndex = 0; byteIndex < ackList.length; byteIndex++) {
                byte ackByte = ackList[byteIndex];

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
                            //帧接收时间
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
            }
        }
    }


    //已经确认的帧数量
    public int getConfirmedCount(){
        return ackedSequences.size();
    }




}
