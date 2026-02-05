package com.bit.coin.broadcast;

public class BroadcastTest {
    public static void main(String[] args) {
        // 1. 获取广播器实例
        MessageBroadcaster broadcaster = MessageBroadcaster.getInstance();

        // 2. 注册两个监听器
        broadcaster.registerListener(new LogMessageListener());
        broadcaster.registerListener(new BusinessMessageListener());

        // 3. 广播订单消息
        BroadcastMessage orderMsg = new BroadcastMessage("ORDER", "用户下单成功，订单号：20260205001");
        broadcaster.broadcast(orderMsg);

        // 4. 广播系统消息
        BroadcastMessage systemMsg = new BroadcastMessage("SYSTEM", "系统将于23:00进行维护");
        broadcaster.broadcast(systemMsg);

        // 5. 移除业务监听器
        broadcaster.removeListener("BUSINESS_LISTENER");

        // 6. 再次广播系统消息（仅日志监听器接收）
        BroadcastMessage systemMsg2 = new BroadcastMessage("SYSTEM", "维护时间延长至00:00");
        broadcaster.broadcast(systemMsg2);
    }
}
