package com.bit.coin.broadcast;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息广播器：全局唯一的消息发布中心
 */
public class MessageBroadcaster {
    // 单例模式：保证整个应用只有一个广播器
    private static final MessageBroadcaster INSTANCE = new MessageBroadcaster();

    // 存储所有注册的监听器（线程安全的Map）
    private final Map<String, MessageListener> listenerMap = new ConcurrentHashMap<>();

    // 私有构造器：禁止外部new，只能通过getInstance获取实例
    private MessageBroadcaster() {}

    // 获取广播器实例
    public static MessageBroadcaster getInstance() {
        return INSTANCE;
    }

    /**
     * 注册监听器：将监听器加入广播器的管理列表
     * @param listener 要注册的监听器
     */
    public void registerListener(MessageListener listener) {
        if (listener == null || listener.getListenerId() == null) {
            throw new IllegalArgumentException("监听器或监听器ID不能为空");
        }
        listenerMap.put(listener.getListenerId(), listener);
        System.out.println("监听器[" + listener.getListenerId() + "]已注册");
    }

    /**
     * 移除监听器：不再接收广播消息
     * @param listenerId 监听器ID
     */
    public void removeListener(String listenerId) {
        if (listenerId != null && listenerMap.containsKey(listenerId)) {
            listenerMap.remove(listenerId);
            System.out.println("监听器[" + listenerId + "]已移除");
        }
    }

    /**
     * 广播消息：通知所有注册的监听器
     * @param message 要广播的消息
     */
    public void broadcast(BroadcastMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("广播消息不能为空");
        }
        System.out.println("\n开始广播消息：" + message);

        // 遍历所有监听器，触发消息处理
        for (MessageListener listener : listenerMap.values()) {
            try {
                // 调用监听器的消息处理方法
                listener.onMessage(message);
            } catch (Exception e) {
                // 单个监听器异常不影响其他监听器
                System.err.println("监听器[" + listener.getListenerId() + "]处理消息异常：" + e.getMessage());
            }
        }
        System.out.println("消息广播完成");
    }
}