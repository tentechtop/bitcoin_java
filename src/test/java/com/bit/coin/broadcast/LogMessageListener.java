package com.bit.coin.broadcast;

public class LogMessageListener implements MessageListener {
    // 监听器唯一ID
    private static final String LISTENER_ID = "LOG_LISTENER";

    @Override
    public void onMessage(BroadcastMessage message) {
        System.out.println("【日志监听器】接收消息 -> " + message);
        // 实际场景中可将消息写入日志文件/数据库
    }

    @Override
    public String getListenerId() {
        return LISTENER_ID;
    }
}