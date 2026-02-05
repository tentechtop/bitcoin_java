package com.bit.coin.broadcast;

/**
 * 消息监听器接口：所有监听器都要实现这个接口
 */
public interface MessageListener {
    /**
     * 处理广播消息的核心方法
     * @param message 接收到的广播消息
     */
    void onMessage(BroadcastMessage message);

    /**
     * 获取监听器唯一ID（用于注册/移除监听器）
     * @return 监听器ID
     */
    String getListenerId();
}