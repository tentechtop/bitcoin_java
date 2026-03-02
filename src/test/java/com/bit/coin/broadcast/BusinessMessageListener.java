package com.bit.coin.broadcast;

/**
 * 业务监听器：只处理订单类型的消息
 */
public class BusinessMessageListener implements MessageListener {
    private static final String LISTENER_ID = "BUSINESS_LISTENER";

    @Override
    public void onMessage(BroadcastMessage message) {
        // 只处理订单类型的消息
        if ("ORDER".equals(message.getType())) {
            System.out.println("【业务监听器】处理订单消息 -> " + message.getContent());
            // 实际场景中可执行订单创建、支付等业务逻辑
        } else {
            System.out.println("【业务监听器】忽略非订单消息 -> " + message.getType());
        }
    }

    @Override
    public String getListenerId() {
        return LISTENER_ID;
    }
}