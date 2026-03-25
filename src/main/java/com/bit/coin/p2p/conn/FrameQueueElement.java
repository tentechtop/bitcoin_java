package com.bit.coin.p2p.conn;

import lombok.Data;

import java.util.Objects;

/**
 * 帧队列元素（仅保留元数据，不再存储完整QuicFrame，避免重复占用空间）
 * 核心定位信息：dataId + sequence（关联到unAckedDataMap中的完整帧）
 */
@Data
public class FrameQueueElement implements Comparable<FrameQueueElement> {
    // 优先级：重传帧（高）=0，新帧（低）=1
    public static final int PRIORITY_RETRY = 0;
    public static final int PRIORITY_NORMAL = 1;

    // 移除：private QuicFrame frame; 不再存储完整帧
    private int priority;             // 优先级
    private int frameSize;            // 帧总字节数（frameTotalLength）
    private long dataId;              // 所属数据ID
    private int sequence;             // 帧序列号
    private long enqueueTime;         // 入队时间（用于清理最旧帧）
    private long sendTime;            // 发送时间（已发送未确认时赋值）

    /**
     * 构造函数：仅接收元数据，不接收完整QuicFrame
     */
    public FrameQueueElement(long dataId, int sequence, int frameSize, int priority) {
        this.dataId = dataId;
        this.sequence = sequence;
        this.frameSize = frameSize;
        this.priority = priority;
        this.enqueueTime = System.currentTimeMillis();
    }

    // 优先级排序：先按优先级，再按入队时间（同优先级旧帧优先）
    @Override
    public int compareTo(FrameQueueElement o) {
        if (this.priority != o.priority) {
            return Integer.compare(this.priority, o.priority);
        }
        return Long.compare(this.enqueueTime, o.enqueueTime);
    }

    // 唯一标识：dataId+sequence（避免重复）
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FrameQueueElement that = (FrameQueueElement) o;
        return dataId == that.dataId && sequence == that.sequence;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataId, sequence);
    }

    // 生成唯一key（用于已发送未确认映射表）
    public String getUniqueKey() {
        return dataId + "_" + sequence;
    }
}