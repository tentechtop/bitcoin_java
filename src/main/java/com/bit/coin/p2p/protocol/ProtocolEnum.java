package com.bit.coin.p2p.protocol;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


/**
 * 协议枚举（作为注册Key）
 */
@Getter
@Slf4j
public enum ProtocolEnum {
    PING_V1(0, "/ping/1.0.0",false),
    PONG_V1(1, "/pong/1.0.0",false),

    CHAIN_V1(2, "/chain/1.0.0",true),//需要返回chain
    BLOCK_V1(3, "/block/1.0.0",true),//需要返回block


    TEXT_V1(6, "/text/1.0.0",false),
    TEXT_V2(7, "/text/2.0.0",true),


    //收到FinNode请求 回复FindNode结果  负载是需要查询的节点ID 32位
    P2P_Find_Node_Req(8, "/P2P_Find_Node_Req/1.0.0",true),
    //广播简约资源消息 资源类型 区块 交易  无回复 [hash 32字节][type 1字节 0区块 1交易]
    P2P_Broadcast_Simple_Resource(9, "/P2P_Broadcast_Simple_Resource/1.0.0",false),
    //请求资源消息
    P2P_Get_Resource_Req(10, "/P2P_Get_Resource_Req/1.0.0",false),
    //接收区块消息 无回复
    P2P_Receive_Block(11, "/P2P_Receive_Block/1.0.0",false),
    //接收交易消息 无回复
    P2P_Receive_Transaction(12, "/P2P_Receive_Transaction/1.0.0",false),
    //向目标节点请求一个区块
    P2P_Query_Block_By_Hash(13, "/P2P_Query_Block_By_Hash/1.0.0",true),
    P2P_Query_Block_By_Height(14, "/P2P_Query_Block_By_Height/1.0.0",true),
    //通过本地路标找到共同祖先 返回值是一个路标
    P2P_Query_Common_Ancestor(15, "/P2P_Query_Common_Ancestor/1.0.0",true),


    Handshake_Success_V1(16, "/handshakeSuccess/1.0.0",false),


    ;

    // Getter
    // 协议编码（对应P2PMessage的type字段）
    private final int code;
    // 协议字符串标识
    private final String protocol;
    // 是否有返回值 true/有返回值 false/无返回值
    private final boolean hasResponse;


    ProtocolEnum(int code, String protocol, boolean hasResponse) {
        this.code = code;
        this.protocol = protocol;
        this.hasResponse = hasResponse;
    }

    // ========== 辅助方法 ==========
    /** 根据code反向查找枚举 */
    public static ProtocolEnum fromCode(int code) {
        for (ProtocolEnum e : values()) {
            if (e.getCode() == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("无效的协议code：" + code);
    }

    /** 根据协议字符串反向查找枚举（标准化处理） */
    public static ProtocolEnum fromProtocol(String protocol) {
        String standardized = protocol.trim().toLowerCase().replaceAll("/+", "/").replaceAll("/$", "");
        for (ProtocolEnum e : values()) {
            if (e.getProtocol().equals(standardized)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未注册的协议字符串：" + protocol);
    }

}