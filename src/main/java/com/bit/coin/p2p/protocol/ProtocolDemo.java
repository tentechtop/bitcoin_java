package com.bit.coin.p2p.protocol;

import com.bit.coin.p2p.protocol.impl.BlockProtocolHandler;
import com.bit.coin.p2p.protocol.impl.TxHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ProtocolDemo {

    public static void main(String[] args) throws Exception {

        BlockProtocolHandler blockProtocolHandler = new BlockProtocolHandler();

        // 1. 获取注册表单例
        ProtocolRegistry protocolRegistry = ProtocolRegistry.getInstance();

        // 2. 注册有返回值的协议（CHAIN_V1）
        protocolRegistry.registerResultHandler(ProtocolEnum.CHAIN_V1,  blockProtocolHandler);

        // 3. 注册无返回值的协议（BLOCK_V1）
        protocolRegistry.registerResultHandler(ProtocolEnum.BLOCK_V1, requestParams -> {
            log.info("处理区块请求，无返回值，参数：{}", requestParams);
            // 无返回值：直接返回null（Lambda中可省略return null，默认返回null）
            //即使未查询到消息 也应该返回一个标准的返回结果
            return new byte[]{};
        });

        TxHandler txHandler = new TxHandler();
        protocolRegistry.registerVoidHandler(ProtocolEnum.zero_V1, txHandler);

        // 4. 调用有返回值的协议
        P2PMessage chainRequest = new P2PMessage();// 模拟请求参数
        byte[] chainResult = protocolRegistry.handle(ProtocolEnum.CHAIN_V1, chainRequest);
        log.info("链查询处理结果：{}", chainResult);

        // 5. 调用无返回值的协议
        P2PMessage blockRequest = new P2PMessage();
        byte[] blockResult = protocolRegistry.handle(ProtocolEnum.BLOCK_V1, blockRequest);
        log.info("区块处理结果（无返回值）：{}", blockResult); // 输出null

        // 6. 也可以通过code调用（适配P2PMessage的type字段）
        byte[] resultByCode = protocolRegistry.handleByCode(1, chainRequest);
        log.info("通过code调用CHAIN_V1的结果：{}", resultByCode);

        Map<ProtocolEnum, ProtocolHandler> handlerMap = protocolRegistry.getHandlerMap();
        ProtocolHandler protocolHandler = handlerMap.get(ProtocolEnum.CHAIN_V1);
        byte[] handle = protocolHandler.handle(new P2PMessage());
        log.info("BlockHeader反序列化结果：{}", handle);

        ProtocolHandler protocolHandler1 = handlerMap.get(ProtocolEnum.zero_V1);
        byte[] handle1 = protocolHandler1.handle(new P2PMessage());
    }

    // 模拟BlockHeader类（贴合你的代码）
    static class BlockHeader {
        private int slot;

        public static BlockHeader deserialize(byte[] data) {
            BlockHeader header = new BlockHeader();
            // 模拟反序列化逻辑
            header.slot = 0;
            return header;
        }

        public byte[] serialize() {
            // 模拟序列化逻辑
            return new byte[]{0, 1, 2};
        }

        public void setSlot(int slot) {
            this.slot = slot;
        }
    }
}