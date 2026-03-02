package com.bit.coin.p2p.protocol;


import com.bit.coin.proto.Structure;
import com.bit.coin.utils.UUIDv7Generator;
import com.google.protobuf.ByteString;
import lombok.Data;
import org.bitcoinj.core.Base58;

import java.io.IOException;
import java.util.Arrays;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;


/**
 * Solana P2P网络消息协议实体
 * 核心字段遵循网络字节序（大端序），适配跨节点/跨语言传输
 * 字段规范：
 * - senderId：32字节Solana公钥（Base58编码后为字符串）
 * - messageId：16字节UUID V7（分布式唯一消息ID）
 * - requestId：16字节UUID V7（请求响应关联ID，全零数组代表非请求/响应消息）
 * - reqResFlag：请求/响应标识（0=请求，1=响应，仅当requestId非全零时有效）
 * - type：消息类型（如PING=1, PONG=2, TRANSACTION=3等）
 * - length：data字段长度（protobuf序列化后，避免NPE）
 * - version：协议版本（默认1，最小1，最大Short.MAX_VALUE）
 * - data：protobuf序列化后的业务数据
 */
@Data
public class P2PMessage {
    // ===================== 常量定义（公开，方便外部使用） =====================
    /** 最小协议版本号（从1开始，0代表未初始化） */
    public static final short MIN_VERSION = 1;
    /** 最大协议版本号（Short最大值，可根据业务扩展） */
    public static final short MAX_VERSION = Short.MAX_VALUE;
    /** Solana公钥固定长度（字节） */
    public static final int SOLANA_PUBKEY_LENGTH = 32;
    /** UUID V7固定长度（字节） */
    public static final int UUID_V7_LENGTH = 16;
    /** 请求标识（reqResFlag=0） */
    public static final byte REQ_FLAG = 0x00;
    /** 响应标识（reqResFlag=1） */
    public static final byte RES_FLAG = 0x01;

    // ===================== 核心字段 =====================
    private byte[] senderId;       // 32字节Solana公钥（消息发送者ID）
    private byte[] messageId;      // 16字节UUID V7（分布式消息唯一ID）
    private byte[] requestId;      // 16字节UUID V7（请求响应关联ID，全零=非请求/响应）
    private byte reqResFlag;       // 请求/响应标识：0=请求，1=响应（仅requestId非全零时有效）
    private int type;              // 消息类型（转发至对应处理器）
    private int length;            // data字段长度（protobuf序列化后）
    private short version = 1;     // 协议版本（默认1，防篡改）  //从版本2开始都是密文传输 读取data前需要解密
    private byte[] data;           // 业务数据（protobuf序列化，JVM数组长度为int）


    // ===================== 非核心字段 无需序列化反序列化 用于转移数据 =====================



    // ===================== 核心逻辑方法（修复+优化） =====================
    /**
     * 判断是否为请求/响应类消息（如ping/pong）
     * 核心逻辑：requestId非空且不是全零数组
     */
    public boolean isReqRes() {
        return requestId != null && requestId.length == UUID_V7_LENGTH && !isAllZero(requestId);
    }

    /**
     * 判断是否为请求消息（如ping）
     * 前提：requestId非全零 + reqResFlag=0
     */
    public boolean isRequest() {
        return isReqRes() && reqResFlag == REQ_FLAG;
    }

    /**
     * 判断是否为响应消息（如pong）
     * 前提：requestId非全零 + reqResFlag=1
     */
    public boolean isResponse() {
        return isReqRes() && reqResFlag == RES_FLAG;
    }

    // ===================== 字段设置方法（增强校验） =====================
    /**
     * 设置发送者ID（Solana公钥），强制校验32字节长度
     */
    public void setSenderId(byte[] senderId) {
        if (senderId != null && senderId.length != SOLANA_PUBKEY_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("发送者ID必须为%d字节（Solana公钥），当前长度：%d",
                            SOLANA_PUBKEY_LENGTH, senderId.length)
            );
        }
        this.senderId = senderId;
    }

    /**
     * 设置消息ID（UUID V7），强制校验16字节长度
     */
    public void setMessageId(byte[] messageId) {
        if (messageId != null && messageId.length != UUID_V7_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("消息ID必须为%d字节（UUID V7），当前长度：%d",
                            UUID_V7_LENGTH, messageId.length)
            );
        }
        this.messageId = messageId;
    }

    /**
     * 设置请求响应ID（UUID V7），强制校验16字节长度（全零=非请求/响应）
     */
    public void setRequestId(byte[] requestId) {
        if (requestId != null && requestId.length != UUID_V7_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("请求响应ID必须为%d字节（UUID V7），当前长度：%d",
                            UUID_V7_LENGTH, requestId.length)
            );
        }
        this.requestId = requestId;
    }

    /**
     * 设置请求/响应标识，仅允许0或1
     */
    public void setReqResFlag(byte reqResFlag) {
        if (reqResFlag != REQ_FLAG && reqResFlag != RES_FLAG) {
            throw new IllegalArgumentException(
                    String.format("请求响应标识仅允许%d（请求）或%d（响应），当前值：%d",
                            REQ_FLAG, RES_FLAG, reqResFlag)
            );
        }
        this.reqResFlag = reqResFlag;
    }

    /**
     * 设置业务数据，自动同步length字段（防御null）
     */
    public void setData(byte[] data) {
        this.data = data;
        this.length = data == null ? 0 : data.length;
    }

    /**
     * 设置协议版本号（int重载，防超出short范围）
     */
    public void setVersion(int version) {
        if (version < MIN_VERSION || version > MAX_VERSION) {
            throw new IllegalArgumentException(
                    String.format("协议版本号必须在[%d, %d]范围内，当前值：%d",
                            MIN_VERSION, MAX_VERSION, version)
            );
        }
        this.version = (short) version;
    }

    /**
     * 设置协议版本号（short重载）
     */
    public void setVersion(short version) {
        if (version < MIN_VERSION || version > MAX_VERSION) {
            throw new IllegalArgumentException(
                    String.format("协议版本号必须在[%d, %d]范围内，当前值：%d",
                            MIN_VERSION, MAX_VERSION, version)
            );
        }
        this.version = version;
    }

    // ===================== 工具方法（适配Solana+调试） =====================
    /**
     * 获取senderId的Base58字符串（Solana生态标准格式）
     */
    public String getSenderIdBase58() {
        return isValidSenderId() ? Base58.encode(senderId) : null;
    }

    /**
     * 获取messageId的16进制字符串（方便日志/调试）
     */
    public String getMessageIdHex() {
        return messageId != null ? bytesToHex(messageId) : null;
    }

    /**
     * 获取requestId的16进制字符串（方便日志/调试）
     */
    public String getRequestIdHex() {
        return requestId != null ? bytesToHex(requestId) : null;
    }

    /**
     * 校验senderId是否为合法Solana公钥（32字节非空）
     */
    public boolean isValidSenderId() {
        return senderId != null && senderId.length == SOLANA_PUBKEY_LENGTH;
    }

    /**
     * 校验messageId是否为合法UUID V7（16字节非空）
     */
    public boolean isValidMessageId() {
        return messageId != null && messageId.length == UUID_V7_LENGTH;
    }

    /**
     * 校验length与data长度是否一致（防篡改/序列化错误）
     */
    public boolean isLengthConsistent() {
        return (data == null && length == 0) || (data != null && data.length == length);
    }

    /**
     * 校验版本号是否合法
     */
    public boolean isValidVersion() {
        return version >= MIN_VERSION;
    }

    // ===================== 重写toString =====================
    @Override
    public String toString() {
        return "P2PMessage{" +
                "senderId=" + getSenderIdBase58() +
                ", messageId=" + getMessageIdHex() +
                ", requestId=" + getRequestIdHex() +
                ", reqResFlag=" + reqResFlag +
                ", type=" + type +
                ", length=" + length +
                ", version=" + version +
                '}';
    }

    /**
     * 判断字节数组是否全零（用于requestId判定）
     */
    private static boolean isAllZero(byte[] bytes) {
        if (bytes == null) return true;
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }

// ===================== 新增：请求/响应消息的核心约束 =====================
    /**
     * 标记为请求消息（自动设置reqResFlag=0 + requestId=当前messageId）
     * 前提：messageId必须已设置（非空且16字节）
     */
    public void markAsRequest() {
        if (!isValidMessageId()) {
            throw new IllegalStateException("请求消息必须先设置合法的messageId（16字节UUID V7）");
        }
        this.reqResFlag = REQ_FLAG;
        this.requestId = this.messageId.clone(); // 深拷贝，避免后续修改messageId影响requestId
    }

    /**
     * 标记为响应消息（自动设置reqResFlag=1 + requestId=请求的messageId）
     * @param requestMessageId 对应的请求消息ID（16字节UUID V7）
     */
    public void markAsResponse(byte[] requestMessageId) {
        if (requestMessageId == null || requestMessageId.length != UUID_V7_LENGTH) {
            throw new IllegalArgumentException("响应消息的requestId必须是16字节的请求messageId");
        }
        this.reqResFlag = RES_FLAG;
        this.requestId = requestMessageId.clone();
    }

    /**
     * 标记为普通消息（非请求/响应，自动设置reqResFlag=0 + requestId=全零数组）
     */
    public void markAsNormalMessage() {
        this.reqResFlag = REQ_FLAG; // 普通消息默认reqResFlag=0（无意义）
        this.requestId = new byte[UUID_V7_LENGTH]; // 全零数组
    }

    /**
     * 校验请求/响应消息的requestId规则是否合法
     */
    public boolean isReqResIdValid() {
        if (!isReqRes()) {
            return true; // 非请求/响应消息，无需校验
        }
        // 请求消息：requestId必须等于自身messageId
        if (isRequest()) {
            return Arrays.equals(this.requestId, this.messageId);
        }
        // 响应消息：requestId必须是16字节非全零（无需等于自身messageId）
        return this.requestId != null && this.requestId.length == UUID_V7_LENGTH && !isAllZero(this.requestId);
    }


    // ===================== 新增：静态构建方法（简化外部调用） =====================
    /**
     * 构建请求消息（封装核心逻辑，无需手动设置requestId/reqResFlag）
     * @param senderId 发送者Solana公钥（32字节）
     * @param protocolEnum 消息类型（如PING=1） 协议类型
     * @param data 业务数据（protobuf序列化字节数组）
     * @return 标准化的请求消息
     */
    public static P2PMessage newRequestMessage(byte[] senderId, ProtocolEnum protocolEnum, byte[] data) {
        P2PMessage request = new P2PMessage();
        byte[] msgId = UUIDv7Generator.generateBytesId();
        byte[] reqId = UUIDv7Generator.generateBytesId();
        // 设置核心字段（自动校验）
        request.setSenderId(senderId);
        request.setMessageId(msgId);
        request.setRequestId(reqId);
        request.setType(protocolEnum.getCode());// 消息类型
        request.setData(data);
        // 自动标记为请求消息（绑定requestId=messageId + reqResFlag=0）
        request.markAsRequest();
        return request;
    }

    /**
     * 构建响应消息（关联原请求ID，自动设置reqResFlag=1）
     * @param senderId 响应发送者Solana公钥（32字节）
     * @param protocolEnum 响应消息类型枚举（如PONG=2）
     * @param reqId 原请求消息（用于关联requestId）
     * @param data 响应业务数据（protobuf序列化字节数组）
     * @return 标准化的响应消息
     */
    public static P2PMessage newResponseMessage(byte[] senderId, ProtocolEnum protocolEnum,
                                                byte[] reqId, byte[] data) {
        // 参数前置校验
        if (protocolEnum == null) {
            throw new IllegalArgumentException("消息类型枚举不能为空");
        }
        P2PMessage response = new P2PMessage();
        // 生成响应自身的唯一消息ID（UUID V7）
        byte[] respMsgId = UUIDv7Generator.generateBytesId();
        // 设置核心字段
        response.setSenderId(senderId);
        response.setMessageId(respMsgId);//保障唯一性
        response.setRequestId(reqId);//用于配对请求
        response.setType(protocolEnum.getCode());
        response.setData(data);
        // 自动标记为响应消息（关联原请求的messageId作为requestId）
        response.markAsResponse(reqId);
        return response;
    }



    /**
     * 构建普通消息（非请求/响应，自动设置requestId为全零数组）
     * @param senderId 发送者Solana公钥（32字节）
     * @param protocolEnum 消息类型枚举（如TRANSACTION=3）
     * @param data 业务数据（protobuf序列化字节数组）
     * @return 标准化的普通消息
     */
    public static P2PMessage newNormalMessage(byte[] senderId, ProtocolEnum protocolEnum, byte[] data) {
        // 参数前置校验
        if (protocolEnum == null) {
            throw new IllegalArgumentException("消息类型枚举不能为空");
        }
        P2PMessage normalMsg = new P2PMessage();
        // 生成唯一消息ID（UUID V7）
        byte[] msgId = UUIDv7Generator.generateBytesId();
        // 设置核心字段
        normalMsg.setSenderId(senderId);
        normalMsg.setMessageId(msgId);
        normalMsg.setType(protocolEnum.getCode());
        normalMsg.setData(data);
        // 自动标记为普通消息（requestId全零 + reqResFlag=0）
        normalMsg.markAsNormalMessage();
        return normalMsg;
    }


    // ===================== 序列化/反序列化核心方法 =====================

    /**
     * 序列化当前对象为字节数组（通过Protobuf）
     */
    public byte[] serialize() throws IOException {
        return toProto().toByteArray();
    }

    /**
     * 从字节数组反序列化为P2PMessage（通过Protobuf）
     */
    public static P2PMessage deserialize(byte[] data)  {
        try {
            Structure.ProtoP2pMessage proto = Structure.ProtoP2pMessage.parseFrom(data);
            return fromProto(proto);
        }catch (Exception e){
            return null;
        }
    }

    /**
     * 转换为Protobuf对象
     */
    public Structure.ProtoP2pMessage toProto() {
        Structure.ProtoP2pMessage.Builder builder = Structure.ProtoP2pMessage.newBuilder();

        // 处理字节类型字段
        if (senderId != null) {
            builder.setSenderId(ByteString.copyFrom(senderId));
        }
        if (messageId != null) {
            builder.setMessageId(ByteString.copyFrom(messageId));
        }
        if (requestId != null) {
            builder.setRequestId(ByteString.copyFrom(requestId));
        }
        if (data != null) {
            builder.setData(ByteString.copyFrom(data));
        }

        // 处理数值类型字段（注意protobuf中uint32对应Java的int，reqResFlag转uint32）
        builder.setReqResFlag((int) reqResFlag) // byte转uint32（兼容protobuf无byte类型）
                .setType(type)
                .setLength(length)
                .setVersion((int) version); // short转uint32（避免符号问题）

        return builder.build();
    }

    /**
     * 从Protobuf对象转换为P2PMessage
     */
    public static P2PMessage fromProto(Structure.ProtoP2pMessage proto) {
        P2PMessage message = new P2PMessage();

        // 处理字节类型字段
        if (!proto.getSenderId().isEmpty()) {
            message.setSenderId(proto.getSenderId().toByteArray());
        }
        if (!proto.getMessageId().isEmpty()) {
            message.setMessageId(proto.getMessageId().toByteArray());
        }
        if (!proto.getRequestId().isEmpty()) {
            message.setRequestId(proto.getRequestId().toByteArray());
        }
        if (!proto.getData().isEmpty()) {
            message.setData(proto.getData().toByteArray());
        }

        // 处理数值类型字段（还原类型，注意范围校验）
        message.setReqResFlag((byte) proto.getReqResFlag()); // uint32转byte（仅0/1，安全）
        message.setType(proto.getType());
        message.setLength(proto.getLength());
        message.setVersion((short) proto.getVersion()); // uint32转short（已校验范围）

        return message;
    }

}