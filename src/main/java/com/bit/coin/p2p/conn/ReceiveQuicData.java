package com.bit.coin.p2p.conn;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.bit.coin.database.rocksDb.RocksDb.intToBytes;
import static com.bit.coin.p2p.conn.QuicConstants.pushCompleteMsg;

@Slf4j
@Data
public class ReceiveQuicData extends QuicData{

    //开始接收时间
    private long startReceiveTime;

    //超时时间 TODO 会按照平均时间计算
    private long timeout = 2500;//目前是2.5秒超时

    //过期时间 TODO 会按照平均时间计算
    private long expireTime = 5000;//目前是5秒过期

    //是否完成接收
    private volatile boolean isCompleted = false;
    // 已经接收到的帧序列号
    private final Set<Integer> receivedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Deque<Integer> latestReceivedSequences = new ConcurrentLinkedDeque<>();


    //自适应ACK策略
    private int lastAckSize = 0;
    private long lastAckTime = System.currentTimeMillis();
    private static final int ACK_BATCH_SIZE = 10;  // 每10帧发送一次批量ACK
    private static final long ACK_MAX_DELAY_MS = 100;  // 最大延迟100ms




    synchronized public ReceiveResult handleFrame(QuicFrame quicFrame,int receiveWindow) {
        int sequence = quicFrame.getSequence();
        int total = quicFrame.getTotal();
        long dataId = quicFrame.getDataId();
        long connectionId = quicFrame.getConnectionId();


        // ========== 重复帧处理 ==========
        if (receivedSequences.contains(sequence)) {
            log.debug("[重复帧] 连接ID:{} 数据ID:{} 序列号:{} 总帧数:{}，直接回复ACK",
                    connectionId, dataId, sequence, total);
            QuicFrame ackFrame = buildAckFrame(quicFrame,receiveWindow);
            return new ReceiveResult(false,isCompleted, ackFrame);
        }
        if (getFrameArray() == null) {
            setFrameArray(new QuicFrame[total]);
            setTotal(total);
            setDataId(dataId);
            setConnectionId(connectionId);
            log.debug("[初始化数据] 连接ID:{} 数据ID:{} 总帧数:{}", connectionId, dataId, total);
        }
        getFrameArray()[sequence] = quicFrame;
        receivedSequences.add(sequence);
        updateLatestReceivedSequences(sequence);
        log.debug("[接收帧] 连接ID:{} 数据ID:{} 序列号:{} 已接收:{}/{}",
                connectionId, dataId, sequence, receivedSequences.size(), total);
        if (receivedSequences.size() == total) {
            log.info("所有帧接收完成");
            // 所有帧接收完成
            handleReceiveSuccess();
            //构建一个ALLReceive帧
            //本地接收窗口
            log.debug("本地接收窗口{}",receiveWindow);
            QuicFrame ackFrame = buildAllAckFrame(quicFrame,receiveWindow);
            //立即返回一个全部接收完成帧
            return new ReceiveResult(true,true, ackFrame);
        }else{
            // 计算当前已接收帧数、距离上次ACK的耗时
            int currentReceivedSize = receivedSequences.size();
            long elapsedSinceLastAck = System.currentTimeMillis() - lastAckTime;
            // 计算从上次ACK后新收到的帧数
            int newReceivedFrames = currentReceivedSize - lastAckSize;

            // ========== 自适应ACK触发逻辑（优先级：超时 > 新收10帧） ==========
            boolean shouldAck = false;

            // 第一步：先判断是否超时（超时直接触发ACK）
            if (elapsedSinceLastAck > ACK_MAX_DELAY_MS) {
                shouldAck = true;

                log.debug("[触发ACK-超时] 连接ID:{} 数据ID:{} 超时{}ms（阈值{}ms），发送位图ACK",
                        quicFrame.getConnectionId(), quicFrame.getDataId(), elapsedSinceLastAck, ACK_MAX_DELAY_MS);
            }
            // 第二步：未超时则判断是否新收到10帧
            else if (newReceivedFrames >= ACK_BATCH_SIZE) {
                shouldAck = true;
                log.debug("[触发ACK-新收帧数] 连接ID:{} 数据ID:{} 新收{}帧（阈值{}帧），发送位图ACK",
                        quicFrame.getConnectionId(), quicFrame.getDataId(), newReceivedFrames, ACK_BATCH_SIZE);
            }
            if (shouldAck) {
                // 发送批量位图ACK，并更新ACK状态
                QuicFrame ackFrame = buildBatchAckFrame(quicFrame, receiveWindow);
                lastAckSize = currentReceivedSize;
                lastAckTime = System.currentTimeMillis();
                return new ReceiveResult(true, false, ackFrame);
            } else {
                // 不触发ACK，返回空
                return new ReceiveResult(true, false, null);
            }
        }
    }

    /**
     * 更新最新接收的序列号队列（去重 + 限制最多10个）
     * @param sequence 刚接收的序列号
     */
    private void updateLatestReceivedSequences(int sequence) {
        // 1. 去重：如果队列中已有该序列号，先移除（保证最后出现在队尾，即最新）
        latestReceivedSequences.remove(sequence);
        // 2. 添加到队尾（最新接收的在最后）
        latestReceivedSequences.offerLast(sequence);

        // 3. 限制长度：超过10个则移除队首（最旧的）
        while (latestReceivedSequences.size() > QuicFrame.BATCH_ACK_MAX_FRAMES) {
            latestReceivedSequences.pollFirst();
        }
    }


    private QuicFrame buildAckFrame(QuicFrame quicFrame,int receiveWindow) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        QuicFrame ackFrame =  new QuicFrame();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(sequence); // ACK帧序列号固定为0
        ackFrame.setTotal(1); // ACK帧不分片
        ackFrame.setFrameType(QuicFrameEnum.DATA_ACK_FRAME.getCode()); // 自定义：ACK帧类型
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());
        // 计算总长度：固定头部 + 载荷长度
        //null+接收窗口大小
        byte[] bytes = intToBytes(receiveWindow);
        ackFrame.setPayload(bytes);
        ackFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+ackFrame.getPayload().length);
        return ackFrame;
    }



    private QuicFrame buildAllAckFrame(QuicFrame quicFrame,int receiveWindow) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        QuicFrame ackFrame =  new QuicFrame();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(0); // ACK帧序列号固定为0
        ackFrame.setTotal(1); // ACK帧不分片
        ackFrame.setFrameType(QuicFrameEnum.ALL_ACK_FRAME.getCode()); // 自定义：ACK帧类型
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());
        //null+接收窗口大小
        byte[] bytes = intToBytes(receiveWindow);
        ackFrame.setPayload(bytes);
        ackFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+ackFrame.getPayload().length);
        return ackFrame;
    }



    private void handleReceiveSuccess() {
        long connectionId = getConnectionId();
        long dataId = getDataId();
        byte[] combinedFullData = getCombinedFullData();
        if (combinedFullData == null) { // 增加非空校验，避免空指针
            log.error("组合完整数据失败，无法处理");
            return;
        }
        //交付完整数据到下一个处理器
        log.debug("完整数据长度{} 数据ID{}",combinedFullData.length,getDataId());

        log.debug("交付数据时间{}",System.currentTimeMillis());

        //处理数据
        QuicMsg quicMsg = new QuicMsg();
        quicMsg.setConnectionId(getConnectionId());
        quicMsg.setDataId(getDataId());
        quicMsg.setData(combinedFullData);
        pushCompleteMsg(quicMsg);

        isCompleted = true;
    }


    /**
     * 当超时时发送一个位图ACK 帮助发送方快速找到确实的ACK
     * 构建批量ACK帧（8192位比特槽位）
     * 比特槽位中每一位代表对应序列号的帧是否已接收（1=已接收，0=未接收）
     * @param quicFrame 参考数据帧（用于获取连接ID、数据ID等元信息）
     * @return 批量ACK帧
     */
     private QuicFrame buildBitMapAckFrame(QuicFrame quicFrame,int receiveWindow) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int totalFrames = getTotal(); // 总帧数（从当前接收数据的元信息中获取）


        QuicFrame ackFrame = new QuicFrame();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(0); //提供窗口大小
        ackFrame.setTotal(1);
        ackFrame.setFrameType(QuicFrameEnum.BITMAP_ACK_FRAME.getCode()); // 新增批量ACK帧类型
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());

        // 1. 计算比特槽位所需字节数：总帧数向上取整到8的倍数（1字节=8位）
        // +4 字节用于接收窗口
        int byteLength = (totalFrames + 7) / 8 + 4; // 例如2048帧需要256字节（2048/8=256）+ 4字节窗口

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

        // 3. 在 payload 末尾添加接收窗口(4字节,int类型)
        int windowIndex = (totalFrames + 7) / 8; // 比特位图之后的位置
        ackPayload[windowIndex] = (byte) ((receiveWindow >> 24) & 0xFF);       // 高字节
        ackPayload[windowIndex + 1] = (byte) ((receiveWindow >> 16) & 0xFF);
        ackPayload[windowIndex + 2] = (byte) ((receiveWindow >> 8) & 0xFF);
        ackPayload[windowIndex + 3] = (byte) (receiveWindow & 0xFF);      // 低字节

        // 4. 设置帧总长度：固定头部长度 +  payload字节数
        int totalLength = QuicFrame.FIXED_HEADER_LENGTH + byteLength;
        ackFrame.setFrameTotalLength(totalLength);
        ackFrame.setPayload(ackPayload);
        return ackFrame;
    }

    //返回已经接收的帧序号 int[]
    // ====================== 新增核心方法：返回已接收的帧序号int[] ======================
    /**
     * 获取已接收的所有帧序列号（升序排列）
     * <p>
     * 特性：
     * 1. 线程安全：基于并发Set的快照复制，避免遍历过程中数据变更
     * 2. 空值安全：无已接收帧时返回空数组（而非null），避免空指针
     * 3. 有序性：返回数组按序列号升序排列，方便调用方按顺序处理
     * </p>
     * @return 已接收帧的序列号数组（升序），无数据时返回空数组
     */
    public int[] getReceivedSequencesArray() {
        // 1. 复制快照：避免遍历过程中Set结构变化（ConcurrentHashMap的Set迭代器是弱一致性，但复制更安全）
        List<Integer> sequenceList = new ArrayList<>(receivedSequences);

        // 2. 空值处理：无数据时直接返回空数组
        if (sequenceList.isEmpty()) {
            return new int[0];
        }

        // 3. 升序排序：保证返回的序列号按顺序排列（符合帧传输的自然顺序）
        Collections.sort(sequenceList);

        // 4. 转换为int数组（避免包装类拆箱的性能损耗）
        int[] result = new int[sequenceList.size()];
        for (int i = 0; i < sequenceList.size(); i++) {
            result[i] = sequenceList.get(i);
        }

        log.debug("[获取已接收帧序号] 数据ID:{} 已接收帧数:{} 序列号列表:{}",
                getDataId(), result.length, Arrays.toString(result));
        return result;
    }

    /**
     * 重载方法：获取已接收的帧序列号（支持指定排序规则）
     * @param reverse 是否倒序（true=倒序，false=升序）
     * @return 已接收帧的序列号数组，无数据时返回空数组
     */
    public int[] getReceivedSequencesArray(boolean reverse) {
        int[] baseArray = getReceivedSequencesArray();
        if (reverse && baseArray.length > 0) {
            // 倒序排列（比如需要优先处理最新接收的帧时使用）
            int[] reversedArray = new int[baseArray.length];
            for (int i = 0; i < baseArray.length; i++) {
                reversedArray[i] = baseArray[baseArray.length - 1 - i];
            }
            return reversedArray;
        }
        return baseArray;
    }



    /**
     * 构建普通批量ACK帧（最多10帧，确认最新接收的帧）
     * payload结构：1字节数量 + N×4字节序号（N≤10） + 4字节接收窗口
     * @param quicFrame 参考数据帧（获取连接ID、数据ID等元信息）
     * @param receiveWindow 接收窗口大小
     * @return 批量ACK帧
     */
    private QuicFrame buildBatchAckFrame(QuicFrame quicFrame, int receiveWindow) {
        // 1. 基础元信息提取
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();

        // 2. 构建批量ACK帧实例
        QuicFrame batchAckFrame = new QuicFrame();
        batchAckFrame.setConnectionId(connectionId);
        batchAckFrame.setDataId(dataId);
        batchAckFrame.setSequence(0); // ACK帧序列号固定为0
        batchAckFrame.setTotal(1);    // ACK帧不分片，总帧数固定为1
        batchAckFrame.setFrameType(QuicFrameEnum.BATCH_ACK_FRAME.getCode()); // 批量ACK帧类型（0x04）
        batchAckFrame.setRemoteAddress(remoteAddress);

        // 3. 从队列取「最新的10个有效序列号」（核心优化：惰性求值，只取需要的）
        int[] ackSequences = latestReceivedSequences.stream()
                .filter(seq -> seq >= 0 && seq < getTotal()) // 过滤有效序列号（防越界）
                .distinct() // 兜底去重（即使队列有重复，这里也能过滤）
                .sorted(Collections.reverseOrder()) // 倒序：最新接收的在前
                .limit(QuicFrame.BATCH_ACK_MAX_FRAMES) // 只取最新的10个，不管队列多长
                .mapToInt(Integer::intValue)
                .toArray();

        // 4. 兜底校验（无有效序列号时返回单帧ACK）
        if (ackSequences.length == 0) {
            log.warn("[批量ACK异常] 无有效序列号，连接ID:{} 数据ID:{}", connectionId, dataId);
            return buildAckFrame(quicFrame, receiveWindow);
        }

        // 5. 写入payload + 更新统计
        batchAckFrame.writeBatchAckFramePayload(ackSequences, receiveWindow);
        lastAckSize = receivedSequences.size();
        lastAckTime = System.currentTimeMillis();

        log.debug("[构建批量ACK] 连接ID:{} 数据ID:{} 确认最新序列号数:{} 接收窗口:{} 序列号列表:{}",
                connectionId, dataId, ackSequences.length, receiveWindow, Arrays.toString(ackSequences));
        return batchAckFrame;
    }



}
