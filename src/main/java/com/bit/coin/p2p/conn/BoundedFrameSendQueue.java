package com.bit.coin.p2p.conn;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 *新帧生成  →  【待发送队列】  →  取出发送  →  【已发送未确认队列】
 *                                           ↙          ↓          ↘
 *                                 超时重传（移回待发送）  ACK确认（删除）  数据过期（强制删除）
 * 核心状态：待发送队列（pending）→ 已发送未确认（sentUnAcked）→ 确认完成/超时重传/数据过期删除
 */
@Slf4j
public class BoundedFrameSendQueue {
    // ========== 核心存储 ==========
    // 待发送队列（优先级：重传帧 > 新帧）
    private final PriorityBlockingQueue<FrameQueueElement> pendingQueue = new PriorityBlockingQueue<>();
    // 已发送未确认映射表（key=dataId_sequence，value=帧元素）
    private final ConcurrentHashMap<String, FrameQueueElement> sentUnAckedMap = new ConcurrentHashMap<>();
    // 待发送队列已入队的帧（去重）
    private final Set<FrameQueueElement> pendingExistedFrames = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ========== 原子大小统计 ==========
    // 待发送队列总字节数
    private final AtomicLong pendingTotalSize = new AtomicLong(0);
    // 已发送未确认队列总字节数
    private final AtomicLong sentUnAckedTotalSize = new AtomicLong(0);

    // ========== 并发控制锁 ==========
    // 待发送队列操作锁（入队/出队/清理）
    private final ReentrantLock pendingLock = new ReentrantLock();
    // 已发送未确认队列操作锁
    private final ReentrantLock sentUnAckedLock = new ReentrantLock();

    // 关联外部的unAckedDataMap（用于查询完整QuicFrame）
    private final ConcurrentHashMap<Long, SendQuicData> unAckedDataMap;

    // 构造函数：注入unAckedDataMap，用于查询完整帧
    public BoundedFrameSendQueue(ConcurrentHashMap<Long, SendQuicData> unAckedDataMap) {
        this.unAckedDataMap = unAckedDataMap;
    }


    // ==========================================================================
    // 核心方法1：帧元数据入队到「待发送队列」
    // ==========================================================================
    public boolean enqueuePending(long dataId, int sequence, int frameSize, int priority) {
        // 基础校验
        if (dataId <= 0 || sequence < 0 || frameSize <= 0) {
            log.warn("[帧入队] 无效元数据，dataId={}, sequence={}, frameSize={}", dataId, sequence, frameSize);
            return false;
        }

        // 【修复点1】入队前检查：如果dataId已过期/全量ACK，直接丢弃
        SendQuicData sendQuicData = unAckedDataMap.get(dataId);
        if (sendQuicData.isExpired()) {
            log.debug("[帧入队] 丢弃已过期dataId的帧，dataId={}, sequence={}", dataId, sequence);
            return false;
        }

        if (sendQuicData.isCompleted()) {
            log.debug("[帧入队] 丢弃已完成dataId的帧，dataId={}, sequence={}", dataId, sequence);
            return false;
        }


        FrameQueueElement element = new FrameQueueElement(dataId, sequence, frameSize, priority);
        // 1. 去重校验（待发送队列中已存在则跳过）
        if (pendingExistedFrames.contains(element)) {
            log.debug("[帧入队] 帧(dataId={}, sequence={})已在待发送队列，跳过", dataId, sequence);
            return false;
        }

        pendingLock.lock();
        try {
            // 2. 直接入队（移除容量管控逻辑）
            pendingQueue.add(element);
            pendingExistedFrames.add(element);
            pendingTotalSize.addAndGet(frameSize);

            log.debug("[帧入队] 待发送队列新增帧(dataId={}, sequence={})，优先级{}，大小{}B，队列总大小{}MB",
                    dataId, sequence, priority, frameSize, pendingTotalSize.get() / 1024.0 / 1024.0);
            return true;
        } finally {
            pendingLock.unlock();
        }
    }

    // ==========================================================================
    // 核心方法2：批量原子化入队（仅传入元数据列表）
    // ==========================================================================
    public boolean batchEnqueuePending(List<FrameMeta> frameMetas, int priority) {
        // 前置校验1：元数据列表不能为空或空列表
        if (frameMetas == null || frameMetas.isEmpty()) {
            log.warn("[批量帧入队] 元数据列表为空，入队失败");
            return false;
        }

        // 步骤1：批量预校验（所有元数据的有效性+去重+过期检查）
        List<FrameQueueElement> elements = new ArrayList<>(frameMetas.size());
        long totalFrameSize = 0;
        for (int i = 0; i < frameMetas.size(); i++) {
            FrameMeta meta = frameMetas.get(i);
            if (meta.getDataId() <= 0 || meta.getSequence() < 0 || meta.getFrameSize() <= 0) {
                log.error("[批量帧入队] 第{}帧元数据无效，dataId={}, sequence={}, frameSize={}，批量入队失败",
                        i, meta.getDataId(), meta.getSequence(), meta.getFrameSize());
                return false;
            }

            // 【修复点2】批量预校验时检查：如果dataId已过期/全量ACK，直接失败
            SendQuicData sendQuicData = unAckedDataMap.get(meta.getDataId());
            if (sendQuicData.isExpired()) {
                log.error("[批量帧入队] 第{}帧所属dataId={}已过期，批量入队失败", i, meta.getDataId());
                return false;
            }
            if (sendQuicData.isCompleted()) {
                log.error("[批量帧入队] 第{}帧所属dataId={}已完成，批量入队失败", i, meta.getDataId());
                return false;
            }


            FrameQueueElement element = new FrameQueueElement(meta.getDataId(), meta.getSequence(), meta.getFrameSize(), priority);
            if (pendingExistedFrames.contains(element)) {
                log.error("[批量帧入队] 第{}帧(dataId={}, sequence={})已在待发送队列，批量入队失败",
                        i, meta.getDataId(), meta.getSequence());
                return false;
            }
            elements.add(element);
            totalFrameSize += meta.getFrameSize();
        }

        // 步骤2：加锁执行原子化入队（移除容量清理逻辑）
        pendingLock.lock();
        List<FrameQueueElement> enqueuedElements = new ArrayList<>();
        try {
            // 直接批量入队（无容量限制）
            for (FrameQueueElement element : elements) {
                try {
                    pendingQueue.add(element);
                    pendingExistedFrames.add(element);
                    pendingTotalSize.addAndGet(element.getFrameSize());
                    enqueuedElements.add(element);
                } catch (Exception e) {
                    log.error("[批量帧入队] 帧(dataId={}, sequence={})入队异常，触发回滚",
                            element.getDataId(), element.getSequence(), e);
                    rollbackEnqueuedElements(enqueuedElements);
                    return false;
                }
            }

            log.info("[批量帧入队] 共{}帧入队成功，总大小{}B，待发送队列当前大小{}MB",
                    frameMetas.size(), totalFrameSize, pendingTotalSize.get() / 1024.0 / 1024.0);
            return true;
        } finally {
            pendingLock.unlock();
        }
    }


    // ==========================================================================
    // 新增：帧元数据封装类（用于批量入队）
    // ==========================================================================
    public static class FrameMeta {
        private long dataId;
        private int sequence;
        private int frameSize;

        public FrameMeta(long dataId, int sequence, int frameSize) {
            this.dataId = dataId;
            this.sequence = sequence;
            this.frameSize = frameSize;
        }

        // getter
        public long getDataId() { return dataId; }
        public int getSequence() { return sequence; }
        public int getFrameSize() { return frameSize; }
    }


    // ==========================================================================
    // 核心方法：从待发送队列取帧（返回完整QuicFrame，从unAckedDataMap查询）
    // ==========================================================================
    public List<QuicFrame> takePendingToSent(int maxBytes) {
        if (maxBytes <= 0 || pendingQueue.isEmpty()) {
            return Collections.emptyList();
        }

        List<FrameQueueElement> takeElements = new ArrayList<>();
        List<QuicFrame> takeFrames = new ArrayList<>();
        long totalTakeSize = 0;

        pendingLock.lock();
        try {
            FrameQueueElement element;
            while ((element = pendingQueue.poll()) != null && totalTakeSize < maxBytes) {
                long elementSize = element.getFrameSize();

                // 单帧超maxBytes处理
                if (elementSize > maxBytes) {
                    pendingQueue.add(element);
                    log.warn("[取出待发送帧] 帧(dataId={}, sequence={})大小{}B超过本次发送最大限制{}B，跳过本次发送",
                            element.getDataId(), element.getSequence(), elementSize, maxBytes);
                    break;
                }

                // 正常处理：检查剩余空间是否足够
                if (totalTakeSize + elementSize <= maxBytes) {
                    takeElements.add(element);
                    totalTakeSize += elementSize;
                } else {
                    pendingQueue.add(element);
                    break;
                }
            }

            // 从unAckedDataMap查询完整QuicFrame
            for (FrameQueueElement e : takeElements) {
                pendingExistedFrames.remove(e);
                QuicFrame frame = getQuicFrameFromMeta(e);
                if (frame != null) {
                    takeFrames.add(frame);
                } else {
                    log.warn("[取出待发送帧] 未找到完整帧(dataId={}, sequence={})", e.getDataId(), e.getSequence());
                }
            }

            // 更新待发送队列总大小
            if (!takeElements.isEmpty()) {
                long removeSize = takeElements.stream().mapToLong(FrameQueueElement::getFrameSize).sum();
                pendingTotalSize.addAndGet(-removeSize);
            }
        } finally {
            pendingLock.unlock();
        }

        // 加入已发送未确认队列
        if (!takeElements.isEmpty()) {
            addToSentUnAcked(takeElements);
            log.debug("[取出待发送帧] 共{}帧，总大小{}B，待发送队列剩余{}MB，已发送未确认队列总大小{}MB",
                    takeFrames.size(), totalTakeSize, pendingTotalSize.get() / 1024.0 / 1024.0,
                    sentUnAckedTotalSize.get() / 1024.0 / 1024.0);
        }

        return takeFrames;
    }

    // ==========================================================================
    // 辅助方法：通过元数据查询完整QuicFrame
    // ==========================================================================
    private QuicFrame getQuicFrameFromMeta(FrameQueueElement element) {
        long dataId = element.getDataId();
        int sequence = element.getSequence();
        SendQuicData sendQuicData = unAckedDataMap.get(dataId);
        if (sendQuicData == null) {
            log.warn("[查询帧] SendQuicData不存在(dataId={})", dataId);
            return null;
        }
        return sendQuicData.getFrameBySequence(sequence);
    }


    // ==========================================================================
    // 核心方法：按dataId过期删除所有对应帧
    // ==========================================================================
    public int expireDataById(long dataId) {
        if (dataId <= 0) {
            log.warn("[数据过期] 无效dataId={}，跳过删除", dataId);
            return 0;
        }

        int totalDeleted = 0;
        long deletedPendingSize = 0;
        long deletedSentUnAckedSize = 0;
        int pendingDeletedCount = 0;

        // 第一步：删除待发送队列中该dataId的所有帧
        pendingLock.lock();
        List<FrameQueueElement> remainElements = new ArrayList<>();
        FrameQueueElement element;
        try {
            while ((element = pendingQueue.poll()) != null) {
                if (element.getDataId() == dataId) {
                    pendingExistedFrames.remove(element);
                    deletedPendingSize += element.getFrameSize();
                    pendingDeletedCount++;
                    totalDeleted++;
                } else {
                    remainElements.add(element);
                }
            }
            if (!remainElements.isEmpty()) {
                pendingQueue.addAll(remainElements);
            }
            if (deletedPendingSize > 0) {
                pendingTotalSize.addAndGet(-deletedPendingSize);
                log.debug("[数据过期] dataId={}，待发送队列删除{}帧，释放{}B空间，剩余{}MB",
                        dataId, pendingDeletedCount, deletedPendingSize, pendingTotalSize.get() / 1024.0 / 1024.0);
            }
        } finally {
            pendingLock.unlock();
        }

        // 第二步：删除已发送未确认队列中该dataId的所有帧
        sentUnAckedLock.lock();
        try {
            Iterator<Map.Entry<String, FrameQueueElement>> iterator = sentUnAckedMap.entrySet().iterator();
            int sentDeletedCount = 0;
            while (iterator.hasNext()) {
                Map.Entry<String, FrameQueueElement> entry = iterator.next();
                element = entry.getValue();
                if (element.getDataId() == dataId) {
                    deletedSentUnAckedSize += element.getFrameSize();
                    iterator.remove();
                    totalDeleted++;
                    sentDeletedCount++;
                }
            }
            if (deletedSentUnAckedSize > 0) {
                sentUnAckedTotalSize.addAndGet(-deletedSentUnAckedSize);
                log.debug("[数据过期] dataId={}，已发送未确认队列删除{}帧，释放{}B空间，剩余{}MB",
                        dataId, sentDeletedCount, deletedSentUnAckedSize, sentUnAckedTotalSize.get() / 1024.0 / 1024.0);
            }
        } finally {
            sentUnAckedLock.unlock();
        }


        log.info("[数据过期] dataId={} 处理完成，共删除{}帧（待发送{}帧 + 已发送{}帧），释放待发送{}B + 已发送{}B = 总计{}B空间",
                dataId, totalDeleted,
                pendingDeletedCount,
                (totalDeleted - pendingDeletedCount),
                deletedPendingSize, deletedSentUnAckedSize,
                deletedPendingSize + deletedSentUnAckedSize);
        return totalDeleted;
    }


    // ==========================================================================
    // ALLACK批量清理：一次性删除指定dataId的所有帧
    // ==========================================================================
    public int ackAllFramesByDataId(long dataId) {
        if (dataId <= 0) {
            log.warn("[ALLACK清理] 无效dataId={}，跳过清理", dataId);
            return 0;
        }

        int totalCleaned = 0;
        long cleanedPendingSize = 0;
        long cleanedSentUnAckedSize = 0;
        int pendingCleanedCount = 0;

        // 第一步：清理待发送队列中该dataId的所有帧
        pendingLock.lock();
        List<FrameQueueElement> remainElements = new ArrayList<>();
        FrameQueueElement element;
        try {
            while ((element = pendingQueue.poll()) != null) {
                if (element.getDataId() == dataId) {
                    pendingExistedFrames.remove(element);
                    cleanedPendingSize += element.getFrameSize();
                    pendingCleanedCount++;
                    totalCleaned++;
                    log.debug("[ALLACK清理] 待发送队列清理帧(dataId={}, sequence={})，大小{}B",
                            dataId, element.getSequence(), element.getFrameSize());
                } else {
                    remainElements.add(element);
                }
            }
            if (!remainElements.isEmpty()) {
                pendingQueue.addAll(remainElements);
            }
            if (cleanedPendingSize > 0) {
                pendingTotalSize.addAndGet(-cleanedPendingSize);
                log.debug("[ALLACK清理] dataId={}，待发送队列清理{}帧，释放{}B空间，剩余{}MB",
                        dataId, pendingCleanedCount, cleanedPendingSize, pendingTotalSize.get() / 1024.0 / 1024.0);
            }
        } finally {
            pendingLock.unlock();
        }

        // 第二步：清理已发送未确认队列中该dataId的所有帧
        sentUnAckedLock.lock();
        try {
            Iterator<Map.Entry<String, FrameQueueElement>> iterator = sentUnAckedMap.entrySet().iterator();
            int sentCleanedCount = 0;
            while (iterator.hasNext()) {
                Map.Entry<String, FrameQueueElement> entry = iterator.next();
                element = entry.getValue();
                if (element.getDataId() == dataId) {
                    cleanedSentUnAckedSize += element.getFrameSize();
                    iterator.remove();
                    totalCleaned++;
                    sentCleanedCount++;
                    log.debug("[ALLACK清理] 已发送未确认队列清理帧(dataId={}, sequence={})，大小{}B",
                            dataId, element.getSequence(), element.getFrameSize());
                }
            }
            if (cleanedSentUnAckedSize > 0) {
                sentUnAckedTotalSize.addAndGet(-cleanedSentUnAckedSize);
                log.debug("[ALLACK清理] dataId={}，已发送未确认队列清理{}帧，释放{}B空间，剩余{}MB",
                        dataId, sentCleanedCount, cleanedSentUnAckedSize, sentUnAckedTotalSize.get() / 1024.0 / 1024.0);
            }
        } finally {
            sentUnAckedLock.unlock();
        }

        log.info("[ALLACK清理完成] dataId={} 所有帧已确认，共清理{}帧（待发送{}帧 + 已发送{}帧），释放总空间{}B（待发送{}B + 已发送{}B） 在途字节{}",
                dataId, totalCleaned,
                pendingCleanedCount,
                (totalCleaned - pendingCleanedCount),
                cleanedPendingSize + cleanedSentUnAckedSize,
                cleanedPendingSize, cleanedSentUnAckedSize,sentUnAckedTotalSize);

        return totalCleaned;
    }


    private void rollbackEnqueuedElements(List<FrameQueueElement> enqueuedElements) {
        if (enqueuedElements.isEmpty()) {
            return;
        }
        long rollbackSize = 0;
        for (FrameQueueElement element : enqueuedElements) {
            pendingQueue.remove(element);
            pendingExistedFrames.remove(element);
            rollbackSize += element.getFrameSize();
        }
        pendingTotalSize.addAndGet(-rollbackSize);
        log.warn("[批量帧入队回滚] 回滚{}帧，释放{}B空间，待发送队列当前大小{}MB",
                enqueuedElements.size(), rollbackSize, pendingTotalSize.get() / 1024.0 / 1024.0);
    }


    public void ackFrame(long dataId, int sequence) {
        sentUnAckedLock.lock();
        try {
            long cleanSize = 0;
            if (sequence == -1) {
                Iterator<Map.Entry<String, FrameQueueElement>> iterator = sentUnAckedMap.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, FrameQueueElement> entry = iterator.next();
                    FrameQueueElement element = entry.getValue();
                    if (element.getDataId() == dataId) {
                        cleanSize += element.getFrameSize();
                        iterator.remove();
                    }
                }
            } else {
                String uniqueKey = dataId + "_" + sequence;
                FrameQueueElement element = sentUnAckedMap.remove(uniqueKey);
                if (element != null) {
                    cleanSize = element.getFrameSize();
                }
            }

            if (cleanSize > 0) {
                sentUnAckedTotalSize.addAndGet(-cleanSize);
                log.debug("[ACK确认帧] dataId={}, sequence={}，清理{}B，已发送未确认队列剩余{}MB",
                        dataId, sequence, cleanSize, sentUnAckedTotalSize.get() / 1024.0 / 1024.0);
            }
        } finally {
            sentUnAckedLock.unlock();
        }
    }

    public int batchAckFrame(long dataId, int[] sequences) {
        if (dataId <= 0) {
            log.warn("[批量ACK] 无效dataId={}，跳过批量确认", dataId);
            return 0;
        }
        if (sequences == null || sequences.length == 0) {
            log.warn("[批量ACK] dataId={}，确认序列号数组为空，跳过批量确认", dataId);
            return 0;
        }

        int cleanedCount = 0;
        long cleanedSize = 0;

        sentUnAckedLock.lock();
        try {
            for (int sequence : sequences) {
                if (sequence < 0) {
                    log.warn("[批量ACK] dataId={}，非法序列号{}（负数），跳过", dataId, sequence);
                    continue;
                }

                String uniqueKey = dataId + "_" + sequence;
                FrameQueueElement element = sentUnAckedMap.remove(uniqueKey);

                if (element != null) {
                    cleanedCount++;
                    cleanedSize += element.getFrameSize();
                    log.debug("[批量ACK] 清理帧(dataId={}, sequence={})，大小{}B",
                            dataId, sequence, element.getFrameSize());
                } else {
                    log.debug("[批量ACK] 帧(dataId={}, sequence={})不在已发送未确认队列，跳过",
                            dataId, sequence);
                }
            }

            if (cleanedSize > 0) {
                sentUnAckedTotalSize.addAndGet(-cleanedSize);
                log.debug("[批量ACK] dataId={}，批量清理{}帧，释放{}B空间，已发送未确认队列剩余{}MB",
                        dataId, cleanedCount, cleanedSize, sentUnAckedTotalSize.get() / 1024.0 / 1024.0);
            }
        } finally {
            sentUnAckedLock.unlock();
        }

        log.debug("[批量ACK完成] dataId={}，传入{}个序列号，实际清理{}帧，释放总空间{}B",
                dataId, sequences.length, cleanedCount, cleanedSize);
        return cleanedCount;
    }

    public int retransmitFrame(long dataId, long timeoutMs) {
        if (timeoutMs <= 0) {
            return 0;
        }

        List<FrameQueueElement> retransmitElements = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        sentUnAckedLock.lock();
        try {
            Iterator<Map.Entry<String, FrameQueueElement>> iterator = sentUnAckedMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, FrameQueueElement> entry = iterator.next();
                FrameQueueElement element = entry.getValue();
                if (element.getDataId() == dataId && (currentTime - element.getSendTime() > timeoutMs)) {
                    retransmitElements.add(element);
                    iterator.remove();
                    sentUnAckedTotalSize.addAndGet(-element.getFrameSize());
                }
            }
        } finally {
            sentUnAckedLock.unlock();
        }

        int retransmitSuccessCount = 0;
        for (FrameQueueElement element : retransmitElements) {
            element.setPriority(FrameQueueElement.PRIORITY_RETRY);
            element.setEnqueueTime(currentTime);

            boolean enqueueSuccess = enqueuePending(
                    element.getDataId(),
                    element.getSequence(),
                    element.getFrameSize(),
                    element.getPriority()
            );

            if (enqueueSuccess) {
                retransmitSuccessCount++;
                log.debug("[超时重传] 帧(dataId={}, sequence={})移回待发送队列（重传优先级）",
                        element.getDataId(), element.getSequence());
            } else {
                log.warn("[超时重传] 帧(dataId={}, sequence={})重传入队失败（已存在/参数无效/已过期）",
                        element.getDataId(), element.getSequence());
            }
        }

        log.debug("[超时重传] dataId={}，尝试移回{}帧，成功{}帧，已发送未确认队列剩余{}MB",
                dataId, retransmitElements.size(),
                retransmitSuccessCount,
                sentUnAckedTotalSize.get() / 1024.0 / 1024.0);
        return retransmitSuccessCount;
    }


    // ==========================================================================
    // 修复版：添加帧到已发送未确认队列（增加过期检查）
    // ==========================================================================
    private void addToSentUnAcked(List<FrameQueueElement> elements) {
        if (elements.isEmpty()) {
            return;
        }

        sentUnAckedLock.lock();
        try {
            long totalAddSize = 0;
            long currentTime = System.currentTimeMillis();
            int newFrameCount = 0;
            int retransCount = 0;
            int droppedCount = 0; // 新增：统计因过期丢弃的帧数

            for (FrameQueueElement element : elements) {
                // 【修复点5】加入已发送队列前检查：如果dataId已过期/全量ACK，直接丢弃
                SendQuicData sendQuicData = unAckedDataMap.get(element.getDataId());
                if (sendQuicData.isExpired()) {
                    log.debug("[添加已发送帧] 丢弃已过期dataId的帧 | dataId={}, seq={}",
                            element.getDataId(), element.getSequence());
                    continue;
                }
                if (sendQuicData.isCompleted()) {
                    log.debug("[添加已发送帧] 丢弃已完成dataId的帧 | dataId={}, seq={}",
                            element.getDataId(), element.getSequence());
                    continue;
                }


                element.setSendTime(currentTime);
                sentUnAckedMap.put(element.getUniqueKey(), element);

                if (element.getPriority() == FrameQueueElement.PRIORITY_RETRY) {
                    retransCount++;
                    log.debug("[重传帧已发送] 不计入内存统计 | dataId={}, seq={}, size={}B",
                            element.getDataId(), element.getSequence(), element.getFrameSize());
                } else {
                    sentUnAckedTotalSize.addAndGet(element.getFrameSize());
                    totalAddSize += element.getFrameSize();
                    newFrameCount++;
                }
            }
            log.debug("[添加已发送帧] 新帧{}个(+{}B)，重传帧{}个(不计)，当前已发送未确认总大小{}MB",
                    newFrameCount, totalAddSize, retransCount,
                    sentUnAckedTotalSize.get() / 1024.0 / 1024.0);
        } finally {
            sentUnAckedLock.unlock();
        }
    }


    // ========== 废弃容量相关清理方法（保留空实现，避免调用报错） ==========
    private boolean cleanLowPriorityPendingFrames() {
        return false;
    }

    private boolean cleanOldestPendingFrames() {
        return false;
    }

    private boolean cleanOldestSentUnAckedFrames() {
        return false;
    }

    // ========== 辅助方法 ==========
    public double getPendingSizeMB() {
        return pendingTotalSize.get() / 1024.0 / 1024.0;
    }

    public double getSentUnAckedSizeMB() {
        return sentUnAckedTotalSize.get() / 1024.0 / 1024.0;
    }

    public int getSentUnAckedSize() {
        return (int)sentUnAckedTotalSize.get();
    }

    public boolean isPendingEmpty() {
        return pendingQueue.isEmpty();
    }

    public boolean isSentUnAckedEmpty() {
        return sentUnAckedMap.isEmpty();
    }
}