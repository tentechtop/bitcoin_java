package com.bit.coin.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.bit.coin.p2p.quic.QuicConnectionManager.Global_Channel;
import static com.bit.coin.p2p.quic.QuicConstants.*;


@Slf4j
@Data
public class ReceiveQuicData extends QuicData {

    //整体超时
    public  final long GLOBAL_TIMEOUT_MS = 5000;
    // 帧重传间隔 应该计算无错误发送一次数据完整用时
    public  final long RETRANSMIT_INTERVAL_MS = 1000;

    //是否完成接收
    private volatile boolean isComplete = false;
    //是否失败
    private volatile boolean isFailed = false;

    // 已经接收到的帧序列号
    private final Set<Integer> receivedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());


    // 全局定时器
    private volatile Timeout globalTimeout;
    // 传输完成回调
    private Runnable successCallback;
    // 传输失败回调
    private Runnable failCallback;


    //当收到数据帧时
   synchronized public void handleFrame(QuicFrame quicFrame) {
       log.info("接收数据");
        int sequence = quicFrame.getSequence();
        int total = quicFrame.getTotal();
        long dataId = quicFrame.getDataId();
        long connectionId = quicFrame.getConnectionId();

        // ========== 重复帧处理 ==========
        if (receivedSequences.contains(sequence)) {
            log.debug("[重复帧] 连接ID:{} 数据ID:{} 序列号:{} 总帧数:{}，直接回复ACK",
                    connectionId, dataId, sequence, total);
            return;
        }
        if (getFrameArray() == null) {
            setFrameArray(new QuicFrame[total]);
            setTotal(total);
            setDataId(dataId);
            setConnectionId(connectionId);
            log.debug("[初始化数据] 连接ID:{} 数据ID:{} 总帧数:{}", connectionId, dataId, total);
            // 启动全局超时定时器  动态全局超时 根据total动态计算
            startGlobalTimeoutTimer();
        }

        getFrameArray()[sequence] = quicFrame;
        receivedSequences.add(sequence);
        log.debug("[接收帧] 连接ID:{} 数据ID:{} 序列号:{} 已接收:{}/{}",
                connectionId, dataId, sequence, receivedSequences.size(), total);

       if (!isComplete() && !isFailed()){
           int size = receivedSequences.size();
           if ((size>1 && size % 128 == 0) || total<=128 || size==total) {
               QuicFrame ackFrame = buildBatchAckFrame(quicFrame);
               if (ackFrame!=null){
                   ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
                   ackFrame.encode(buf);
                   DatagramPacket packet = new DatagramPacket(buf, ackFrame.getRemoteAddress());
                   Global_Channel.writeAndFlush(packet);
                   ackFrame.release();
               }
           }
       }

        if (receivedSequences.size() == total) {
            log.info("所有帧接收完成");
            // 所有帧接收完成
            handleDataComplete();
        }
    }


    private void startGlobalTimeoutTimer() {
        // 双重校验锁，防止重复创建
        if (globalTimeout == null && !isComplete()) {
            globalTimeout = GLOBAL_TIMER.newTimeout(new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    if (timeout.isCancelled()) {
                        return;
                    }
                    log.error("[全局超时] 连接ID:{} 数据ID:{} 在{}ms内未接收完所有帧，接收失败",
                            getConnectionId(),  getDataId(), GLOBAL_TIMEOUT_MS);
                    handleDataFailure();
                }
            }, GLOBAL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.debug("[全局定时器启动] 连接ID:{} 数据ID:{} 超时时间:{}ms",
                    getConnectionId(), getDataId(), GLOBAL_TIMEOUT_MS);
        }
    }



    private void handleDataComplete() {
        if (isComplete()){
            log.info("已经完成数据交付");
            return;
        }
        //回复ALL_ACK帧
        long connectionId = getConnectionId();
        long dataId = getDataId();
 /*       Thread.ofVirtual().start(() -> {
            QuicFrame ackFrame =  QuicFrame.acquire();
            ackFrame.setConnectionId(connectionId);
            ackFrame.setDataId(dataId);
            ackFrame.setSequence(0); // ACK帧序列号固定为0
            ackFrame.setTotal(getTotal()); //携带全部的帧
            ackFrame.setFrameType(QuicFrameEnum.ALL_ACK_FRAME.getCode()); // 自定义：ACK帧类型
            ackFrame.setRemoteAddress(getRemoteAddress());
            // 计算总长度：固定头部 + 载荷长度
            int totalLength = QuicFrame.FIXED_HEADER_LENGTH;
            ackFrame.setFrameTotalLength(totalLength);
            ackFrame.setPayload(null);
            ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
            ackFrame.encode(buf);
            DatagramPacket packet = new DatagramPacket(buf, ackFrame.getRemoteAddress());
            Global_Channel.writeAndFlush(packet);
            ackFrame.release();
        });*/

        setComplete(true);
        cancelAllRetransmitTimers();
        byte[] combinedFullData = getCombinedFullData();
        if (combinedFullData == null) { // 增加非空校验，避免空指针
            log.error("组合完整数据失败，无法处理");
            return;
        }
        //交付完整数据到下一个处理器
        log.info("完整数据长度{} 数据ID{}",combinedFullData.length,getDataId());

        //处理数据
        QuicMsg quicMsg = new QuicMsg();
        quicMsg.setConnectionId(getConnectionId());
        quicMsg.setDataId(getDataId());
        quicMsg.setData(combinedFullData);
        pushCompleteMsg(quicMsg);

        R_CACHE.put(getDataId(),System.currentTimeMillis());
        deleteReceiveDataByConnectIdAndDataId(getConnectionId(), getDataId());
        if (successCallback != null) {
            successCallback.run();
        }
    }

    /**
     * 处理数据接收失败
     */
    private void handleDataFailure() {
        setFailed(true);
        log.error("[数据接收失败] 连接ID:{} 数据ID:{}", getConnectionId(), getDataId());
        //删除这个数据
        deleteReceiveDataByConnectIdAndDataId(getConnectionId(), getDataId());
        // 取消所有定时器
        cancelAllRetransmitTimers();
        // 释放帧资源
        releaseFrames();
        // 执行失败回调
        if (failCallback != null) {
            failCallback.run();
        }
    }

    private void cancelAllRetransmitTimers() {
        //取消全局
        //取消所有的单个
        if (globalTimeout != null) {
            globalTimeout.cancel();
            globalTimeout = null; // 清空引用，帮助GC
        }
    }

    private void releaseFrames() {
        QuicFrame[] frameArray = getFrameArray();
        if (frameArray == null) return;
        // 遍历释放已接收的QuicFrame（避免内存泄漏）
        for (QuicFrame frame : frameArray) {
            if (frame != null) {
                frame=null;
            }
        }
        // 清空数组引用，帮助GC
        setFrameArray(null);
        // 清空已接收集合
        receivedSequences.clear();
    }


    /**
     * 构建ACK帧
     * @param quicFrame 收到的数据帧
     * @return
     */
    private QuicFrame buildAckFrame(QuicFrame quicFrame) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        QuicFrame ackFrame =  QuicFrame.acquire();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(sequence); // ACK帧序列号固定为0
        ackFrame.setTotal(1); // ACK帧不分片
        ackFrame.setFrameType(QuicFrameEnum.DATA_ACK_FRAME.getCode()); // 自定义：ACK帧类型
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());
        // 计算总长度：固定头部 + 载荷长度
        int totalLength = QuicFrame.FIXED_HEADER_LENGTH;
        ackFrame.setFrameTotalLength(totalLength);
        ackFrame.setPayload(null);
        return ackFrame;
    }


    /**
     * 构建批量ACK帧（8192位比特槽位）
     * 比特槽位中每一位代表对应序列号的帧是否已接收（1=已接收，0=未接收）
     * @param quicFrame 参考数据帧（用于获取连接ID、数据ID等元信息）
     * @return 批量ACK帧
     */
    synchronized private QuicFrame buildBatchAckFrame(QuicFrame quicFrame) {
        if (!isComplete() && !isFailed() && quicFrame!=null){
            long connectionId = quicFrame.getConnectionId();
            long dataId = quicFrame.getDataId();
            int totalFrames = getTotal(); // 总帧数（从当前接收数据的元信息中获取）

            QuicFrame ackFrame = QuicFrame.acquire();
            ackFrame.setConnectionId(connectionId);
            ackFrame.setDataId(dataId);
            ackFrame.setSequence(0); //提供窗口大小
            ackFrame.setTotal(1);
            ackFrame.setFrameType(QuicFrameEnum.BATCH_ACK_FRAME.getCode()); // 新增批量ACK帧类型
            ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());

            // 1. 计算比特槽位所需字节数：总帧数向上取整到8的倍数（1字节=8位）
            int byteLength = (totalFrames + 7) / 8; // 例如2048帧需要256字节（2048/8=256）
            byte[] ackPayload = new byte[byteLength];

            // 2. 填充比特槽位：已接收的序列号对应位置置为1
            for (int sequence : receivedSequences) {
                // 校验序列号合法性（防止越界）
                if (sequence < 0 || sequence >= totalFrames) {
                    log.warn("[批量ACK异常] 序列号超出范围，忽略处理: sequence={}, total={}", sequence, totalFrames);
                    continue;
                }
                // 计算比特位在字节数组中的位置
                int byteIndex = sequence / 8;
                int bitIndex = sequence % 8;
                // 对应比特位置1（采用大端序：第0位表示序列号0）
                ackPayload[byteIndex] |= (byte) (1 << (7 - bitIndex));
            }

            // 3. 设置帧总长度：固定头部长度 +  payload字节数
            int totalLength = QuicFrame.FIXED_HEADER_LENGTH + byteLength;
            ackFrame.setFrameTotalLength(totalLength);
            ackFrame.setPayload(ackPayload);
            return ackFrame;
        }else {
            return null;
        }
    }



}
