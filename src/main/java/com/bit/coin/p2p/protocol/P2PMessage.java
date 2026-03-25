package com.bit.coin.p2p.protocol;


import com.bit.coin.utils.UUIDv7Generator;
import lombok.Data;
import org.bitcoinj.core.Base58;

import java.io.*;
import java.util.Arrays;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;


/**
 * P2P网络消息协议实体
 * 核心字段遵循网络字节序（大端序），适配跨节点/跨语言传输
 */
@Data
public class P2PMessage {
    // ===================== 常量定义（公开，方便外部使用） =====================
    /** 最小协议版本号（从1开始，0代表未初始化） */
    public static final short MIN_VERSION = 1;
    /** 最大协议版本号（Short最大值，可根据业务扩展） */
    public static final short MAX_VERSION = Short.MAX_VALUE;
    /** 公钥固定长度（字节） */
    public static final int PUBKEY_LENGTH = 32;
    /** UUID V7固定长度（字节） */
    public static final int UUID_V7_LENGTH = 16;
    /** 请求标识（reqResFlag=0） */
    public static final byte REQ_FLAG = 0x00;
    /** 响应标识（reqResFlag=1） */
    public static final byte RES_FLAG = 0x01;

    // ===================== 核心字段 =====================
    private byte[] senderId;       // 32字节公钥（消息发送者ID）
    private byte[] messageId;      // 16字节UUID V7（分布式消息唯一ID）
    private byte[] requestId;      // 16字节UUID V7（请求响应关联ID，全零=非请求/响应）
    private byte reqResFlag;       // 请求/响应标识：0=请求，1=响应（仅requestId非全零时有效）
    private int type;              // 消息类型（转发至对应处理器）
    private int length;            // data字段长度
    private short version = 1;     // 协议版本（默认1，防篡改）  //从版本2开始都是密文传输 读取data前需要解密
    private byte[] data;           // 业务数据


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
     * 设置发送者ID（公钥），强制校验32字节长度
     */
    public void setSenderId(byte[] senderId) {
        if (senderId != null && senderId.length != PUBKEY_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("发送者ID必须为%d字节（公钥），当前长度：%d",
                            PUBKEY_LENGTH, senderId.length)
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

    // ===================== 工具方法（适配+调试） =====================
    /**
     * 获取senderId的Base58字符串（生态标准格式）
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
     * 校验senderId是否为合法公钥（32字节非空）
     */
    public boolean isValidSenderId() {
        return senderId != null && senderId.length == PUBKEY_LENGTH;
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
     * @param senderId 发送者公钥（32字节）
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
     * @param senderId 响应发送者公钥（32字节）
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
     * @param senderId 发送者公钥（32字节）
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
     * 序列化当前对象为字节数组（网络字节序/大端序）
     * 字段顺序：version(2) → senderId(32) → messageId(16) → requestId(16) → reqResFlag(1) → type(4) → length(4) → data(length)
     */
    public byte[] serialize() throws IOException {
        // 前置校验：确保对象字段合法，避免序列化非法数据
        if (!isValidVersion()) {
            throw new IllegalStateException("协议版本不合法：" + version);
        }
        if (!isValidSenderId()) {
            throw new IllegalStateException("发送者ID（公钥）不合法，必须是32字节");
        }
        if (!isValidMessageId()) {
            throw new IllegalStateException("消息ID（UUID V7）不合法，必须是16字节");
        }
        if (!isLengthConsistent()) {
            throw new IllegalStateException("data长度与length字段不一致：data长度=" + (data == null ? 0 : data.length) + ", length=" + length);
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // 1. 协议版本（short，2字节，大端）
            dos.writeShort(version);
            // 2. 发送者ID（32字节固定长度）
            dos.write(senderId);
            // 3. 消息ID（16字节固定长度）
            dos.write(messageId);
            // 4. 请求响应ID（16字节固定长度，全零则写全零）
            dos.write(requestId == null ? new byte[UUID_V7_LENGTH] : requestId);
            // 5. 请求/响应标识（1字节）
            dos.writeByte(reqResFlag);
            // 6. 消息类型（int，4字节，大端）
            dos.writeInt(type);
            // 7. data长度（int，4字节，大端）
            dos.writeInt(length);
            // 8. 业务数据（变长，长度=length）
            if (length > 0 && data != null) {
                dos.write(data);
            }

            return baos.toByteArray();
        }
    }

    /**
     * 从字节数组反序列化为P2PMessage（网络字节序/大端序）
     * @param data 序列化后的字节数组
     * @return 还原后的P2PMessage对象
     * @throws IOException 反序列化失败（如字节长度不足、格式错误）
     */
    public static P2PMessage deserialize(byte[] data) throws IOException {
        // 前置校验：输入字节数组不能为空
        if (data == null || data.length < (2 + 32 + 16 + 16 + 1 + 4 + 4)) {
            throw new IllegalArgumentException("反序列化失败：字节数组为空或长度不足（最小长度需75字节）");
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {

            P2PMessage message = new P2PMessage();

            // 1. 读取协议版本（short，2字节）
            short version = dis.readShort();
            message.setVersion(version); // 使用setter做合法性校验

            // 2. 读取发送者ID（32字节）
            byte[] senderId = new byte[PUBKEY_LENGTH];
            dis.readFully(senderId); // 强制读取32字节，不足则抛EOFException
            message.setSenderId(senderId); // 使用setter做合法性校验

            // 3. 读取消息ID（16字节）
            byte[] messageId = new byte[UUID_V7_LENGTH];
            dis.readFully(messageId);
            message.setMessageId(messageId); // 使用setter做合法性校验

            // 4. 读取请求响应ID（16字节）
            byte[] requestId = new byte[UUID_V7_LENGTH];
            dis.readFully(requestId);
            message.setRequestId(requestId); // 使用setter做合法性校验

            // 5. 读取请求/响应标识（1字节）
            byte reqResFlag = dis.readByte();
            message.setReqResFlag(reqResFlag); // 使用setter做合法性校验

            // 6. 读取消息类型（int，4字节）
            int type = dis.readInt();
            message.setType(type);

            // 7. 读取data长度（int，4字节）
            int length = dis.readInt();
            message.setLength(length); // 先设置length，后续设置data时会自动同步

            // 8. 读取业务数据（变长，长度=length）
            byte[] dataBytes = new byte[length];
            if (length > 0) {
                dis.readFully(dataBytes); // 强制读取length字节，不足则抛EOFException
            }
            message.setData(dataBytes); // 使用setter自动同步length（双重校验）

            // 最终校验：length与data长度一致性
            if (!message.isLengthConsistent()) {
                throw new IOException("反序列化失败：length字段与实际data长度不一致");
            }

            return message;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}