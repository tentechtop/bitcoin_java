package com.bit.coin.p2p.conn;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
@Data
public class QuicData {
    private long connectionId;//连接ID
    private long dataId;//数据ID
    private int total;//数据帧总数
    private int size;//字节数量
    private QuicFrame[] frameArray;//帧数据按照序列号存入
    private InetSocketAddress remoteAddress;


    public int getSize(){
        //通过分析frameArray 给出大小
        int size = 0;
        for (QuicFrame frame : frameArray) {
            if (frame != null) {
                size += frame.getPayload().length;
            }
        }
        return size;
    }



    public byte[] getCombinedFullData() {
        // 校验帧数组是否存在
        if (frameArray == null || frameArray.length == 0) {
            log.warn("帧数组为空，无法合并数据 connectionId={}, dataId={}", connectionId, dataId);
            return new byte[0];
        }

        // 校验总帧数与数组长度一致性
        if (total != frameArray.length) {
            log.error("总帧数与帧数组长度不一致，合并失败 connectionId={}, dataId={}, total={}, frameArrayLength={}",
                    connectionId, dataId, total, frameArray.length);
            return new byte[0];
        }

        try {
            // 计算总数据长度
            int totalLength = 0;
            for (QuicFrame frame : frameArray) {
                if (frame == null) {
                    log.warn("发现空帧，跳过计算 connectionId={}, dataId={}", connectionId, dataId);
                    continue;
                }
                byte[] payload = frame.getPayload();
                if (payload != null) {
                    totalLength += payload.length;
                }
            }

            // 初始化结果数组
            byte[] fullData = new byte[totalLength];
            int currentOffset = 0;

            // 合并所有帧的载荷数据
            for (QuicFrame frame : frameArray) {
                if (frame == null) {
                    log.warn("发现空帧，跳过合并 connectionId={}, dataId={}", connectionId, dataId);
                    continue;
                }
                byte[] payload = frame.getPayload();
                if (payload == null || payload.length == 0) {
                    log.debug("帧载荷为空，跳过合并 sequence={}, connectionId={}, dataId={}",
                            frame.getSequence(), connectionId, dataId);
                    continue;
                }

                // 复制当前帧的载荷到结果数组
                System.arraycopy(payload, 0, fullData, currentOffset, payload.length);
                currentOffset += payload.length;
            }

            // 校验合并后的数据长度是否符合预期
            if (currentOffset != totalLength) {
                log.warn("合并后数据长度不匹配，预期={}, 实际={} connectionId={}, dataId={}",
                        totalLength, currentOffset, connectionId, dataId);
            }

            return fullData;
        } catch (Exception e) {
            log.error("合并帧数据失败 connectionId={}, dataId={}", connectionId, dataId, e);
            return new byte[0];
        }
    }

    /**
     * 根据序列号获取帧数据
     *
     * @param sequence 序列号（0 ~ total-1）
     * @return 对应的QuicFrame（null表示不存在）
     */
    public QuicFrame getFrameBySequence(int sequence) {
        // 前置校验
        if (sequence < 0 || sequence >= total) {
            log.warn("序列号非法 connectionId={}, dataId={}, sequence={}, total={}",
                    connectionId, dataId, sequence, total);
            return null;
        }
        if (frameArray == null || frameArray.length != total) {
            log.warn("帧数组异常 connectionId={}, dataId={}, arrayLength={}, total={}",
                    connectionId, dataId, frameArray == null ? 0 : frameArray.length, total);
            return null;
        }
        return frameArray[sequence];
    }
}
