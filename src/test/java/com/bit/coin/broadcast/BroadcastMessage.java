package com.bit.coin.broadcast;

public class BroadcastMessage {
    // 消息类型（如订单、系统通知、用户消息等）
    private String type;
    // 消息具体内容
    private String content;
    // 消息发送时间戳
    private long sendTime;

    // 构造器：创建消息时自动填充发送时间
    public BroadcastMessage(String type, String content) {
        this.type = type;
        this.content = content;
        this.sendTime = System.currentTimeMillis();
    }

    // Getter方法（监听器需要获取消息内容）
    public String getType() { return type; }
    public String getContent() { return content; }
    public long getSendTime() { return sendTime; }

    // 重写toString，方便打印日志
    @Override
    public String toString() {
        return "BroadcastMessage{" +
                "type='" + type + '\'' +
                ", content='" + content + '\'' +
                ", sendTime=" + sendTime +
                '}';
    }
}
