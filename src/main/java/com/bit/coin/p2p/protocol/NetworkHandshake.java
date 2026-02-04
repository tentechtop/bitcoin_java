package com.bit.coin.p2p.protocol;


import com.bit.coin.proto.Structure;
import com.bit.coin.utils.UInt256;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.ByteString;
import lombok.Data;

import java.io.IOException;
import java.net.InetSocketAddress;

@Data
public class NetworkHandshake {
    /**
     * 网络魔法值：固定字节数组（如Solana主网为0x5eyy...）
     * 作用：区分测试网/主网/私有链，防止节点接入错误网络
     */
    private byte[] networkMagic;

    /**
     * 节点硬件标识（可选）：如CPU核心数/内存大小
     * 作用：对方评估本节点的处理能力，优化数据转发策略
     */
    private String hardwareInfo;

    /**
     * 软件版本
     */


    //节点ID 32字节公钥
    private byte[] nodeId;

    // 随机数 UUID v7
    private byte[] nonceId;

    /**
     * 节点版本：如"1.18.17"（Solana版本格式）
     * 作用：协商协议兼容性，低版本节点可被拒绝/降级处理
     */
    private String nodeVersion;


    //协商加密信息 SecretKey 承载协商公钥  //不在网络中传输
    private byte[] sharedSecret;

    //签名
    private byte[] signature;


    // 衍生字段（无需序列化，通过address+port动态构建）
    private InetSocketAddress inetSocketAddress;

    //创世区块Hash
    //节点信息
    //最新顶端区块hash
    private byte[] latestHash;
    //最新顶断区块高度
    private int latestHeight;
    //顶端累计工作总量
    @JsonIgnore
    private UInt256 chainWork = UInt256.fromLong(0L);





    // ========================== 序列化/反序列化核心方法 ==========================

    /**
     * 序列化当前握手对象为字节数组（Protobuf 编码）
     * @return 序列化后的字节数组
     * @throws IOException 序列化失败时抛出
     */
    public byte[] serialize() throws IOException {
        return toProto().toByteArray();
    }

    /**
     * 从字节数组反序列化为 NetworkHandshake 对象
     * @param data 序列化后的字节数组
     * @return 解析后的 NetworkHandshake 实例
     * @throws IOException 反序列化失败时抛出
     */
    public static NetworkHandshake deserialize(byte[] data) throws IOException {
        Structure.ProtoNetworkHandshake proto = Structure.ProtoNetworkHandshake.parseFrom(data);
        return fromProto(proto);
    }

    // ========================== Protobuf 转换方法 ==========================

    /**
     * 将当前对象转换为 Protobuf 生成的 ProtoNetworkHandshake 对象
     * @return ProtoNetworkHandshake 实例
     */
    public Structure.ProtoNetworkHandshake toProto() {
        Structure.ProtoNetworkHandshake.Builder builder = Structure.ProtoNetworkHandshake.newBuilder();

        // 处理字节数组字段（非空则设置）
        if (networkMagic != null && networkMagic.length > 0) {
            builder.setNetworkMagic(ByteString.copyFrom(networkMagic));
        }
        if (nodeId != null && nodeId.length > 0) {
            builder.setNodeId(ByteString.copyFrom(nodeId));
        }
        if (nonceId != null && nonceId.length > 0) {
            builder.setNonceId(ByteString.copyFrom(nonceId));
        }
        if (signature != null && signature.length > 0) {
            builder.setSignature(ByteString.copyFrom(signature));
        }

        // 处理字符串字段（非空则设置）
        if (hardwareInfo != null && !hardwareInfo.isEmpty()) {
            builder.setHardwareInfo(hardwareInfo);
        }
        if (nodeVersion != null && !nodeVersion.isEmpty()) {
            builder.setNodeVersion(nodeVersion);
        }

        if (sharedSecret != null && sharedSecret.length > 0) {
            builder.setSharedSecret(ByteString.copyFrom(sharedSecret));
        }

        if (latestHash != null && latestHash.length > 0) {
            builder.setLatestHash(ByteString.copyFrom(latestHash));
        }
        if (latestHeight != 0) {
            builder.setLatestHeight(latestHeight);
        }
        if (chainWork != null) {
            builder.setTotalWork(ByteString.copyFrom(chainWork.toBytes()));
        }

        return builder.build();
    }

    /**
     * 从 Protobuf 对象转换为 NetworkHandshake 实例
     * @param proto ProtoNetworkHandshake 实例
     * @return NetworkHandshake 实例
     */
    public static NetworkHandshake fromProto(Structure.ProtoNetworkHandshake proto) {
        NetworkHandshake handshake = new NetworkHandshake();

        // 处理字节数组字段（Protobuf的ByteString转字节数组）
        if (!proto.getNetworkMagic().isEmpty()) {
            handshake.setNetworkMagic(proto.getNetworkMagic().toByteArray());
        }
        if (!proto.getNodeId().isEmpty()) {
            handshake.setNodeId(proto.getNodeId().toByteArray());
        }
        if (!proto.getNonceId().isEmpty()) {
            handshake.setNonceId(proto.getNonceId().toByteArray());
        }
        if (!proto.getSignature().isEmpty()) {
            handshake.setSignature(proto.getSignature().toByteArray());
        }

        // 处理字符串字段
        handshake.setHardwareInfo(proto.getHardwareInfo());
        handshake.setNodeVersion(proto.getNodeVersion());
        handshake.setSharedSecret(proto.getSharedSecret().toByteArray());
        handshake.setLatestHash(proto.getLatestHash().toByteArray());
        handshake.setLatestHeight(proto.getLatestHeight());
        handshake.setChainWork(UInt256.fromBytes(proto.getTotalWork().toByteArray()));
        return handshake;
    }
}
