package com.bit.coin.p2p.peer;


import com.bit.coin.proto.Structure;
import com.bit.coin.structure.block.HexByteArraySerializer;
import com.bit.coin.utils.MultiAddress;
import com.bit.coin.utils.UInt256;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.ByteString;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import tools.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 节点信息  按 8 字节对齐：180 ÷ 8 = 22.5 → 补 2 字节，最终Peer 对象本身占 182 字节（对齐后 184 字节）。
 * 5120个节点大约占用 2-3M内存
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class Peer {
    // ==================== 核心标识（唯一确定节点身份） ====================
    /**
     * 节点公钥（32字节，hex字符串）
     * Solana节点的唯一身份标识，用于加密通信、签名验证（如节点消息签名）
     * 例："7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"
     * 节点的公钥 base58编码后的值  Base58.encode(publicKey) 衍生字段 不参与序列化 只在反序列化衍生
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] id;

    //只有自己才保存
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] privateKey;


    // ==================== 网络信息（用于P2P连接） ====================
    /**
     * 节点IP地址（IPv4/IPv6）  通过netty获取真实的IP地址  节点连接引导节点可以获取
     * 例："192.168.1.100" 或 "2001:db8::1"
     */
    private String address;

    /**
     * P2P通信端口
     */
    private int port;

    /**
     * 节点多地址（Multiaddr格式，P2P网络标准地址格式）
     * 整合IP、端口、协议等信息，方便跨网络层解析
     * 例："/ip4/192.168.1.100/tcp/8000/p2p/7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"
     */
    private String multiaddr;
    //get
    public String getMultiaddr() {
        //如果地址端口和节点ID不为空
        if (address != null && port != 0) {
            return MultiAddress.build("ip4", address, MultiAddress.Protocol.QUIC, port, Base58.encode(id)).toRawAddress();
        }
        return null;
    }



    /**
     * 节点支持的通信协议版本
     * 用于确保节点间协议兼容性（如"solana-p2p/1.14.19"）
     */
    private String protocolVersion;

    // ==================== 节点状态（反映节点当前状态） ====================
    /**
     * 节点类型
     */
    private int nodeType;

    /**
     * 是否在线 true在线 / false 离线
     */
    private boolean isOnline;

    /**
     * 节点最新同步的区块槽位（Slot）
     * 反映节点账本同步进度，用于判断节点数据新鲜度
     * 例：198765432
     */
    private long latestSlot;


    // ==================== 能力与属性（描述节点功能） ====================
    /**
     * 节点是否为验证者（参与共识）
     * 等价于 nodeType == NodeType.VALIDATOR，方便快速判断
     */
    private boolean isValidator;

    /**
     * 验证者节点的质押量（SOL，仅验证者有效）
     * 反映节点在共识中的权重（质押量越高，被选为领袖的概率越高）
     */
    private double stakeAmount;

    /**
     * 节点软件版本（如"SOLANA_VERSION=1.14.19"）
     * 用于排查版本兼容问题（如某些功能仅高版本支持）
     */
    private int softwareVersion;

    /**
     * 最后访问时间
     */
    private long lastSeen;
    //
    public String getLastSeenStr(){
        //yyyy-mm-ss hh-mm-ss
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastSeen));
    }



    //节点最新区块hash
    private String hashHex;
    //节点区块链顶端高度
    private int height;
    //顶端累计工作总量
    @JsonIgnore
    private UInt256 chainWork = UInt256.fromLong(0L);
    public BigInteger getChainWorkDecimal(){
        if (chainWork==null){
            chainWork = UInt256.fromLong(0L);
        }
        return chainWork.toBigInteger();
    }





    // 衍生字段（无需序列化，通过address+port动态构建）
    private InetSocketAddress inetSocketAddress;

    public void setInetSocketAddress(InetSocketAddress inetSocketAddress){
        this.inetSocketAddress = inetSocketAddress;
        if (inetSocketAddress != null){
            this.address = inetSocketAddress.getAddress().getHostAddress();
            log.info("网络地址{}",this.address);
            this.port = inetSocketAddress.getPort();
            log.info("网络端口{}",this.port);
        }
    }





    // ==================== 序列化/反序列化 ====================
    /**
     * 序列化当前Peer对象为字节数组（基于Protobuf）
     */
    public byte[] serialize() throws IOException {
        return toProto().toByteArray();
    }

    /**
     * 从字节数组反序列化为Peer对象（基于Protobuf）
     */
    public static Peer deserialize(byte[] data) throws IOException {
        Structure.ProtoPeer protoPeer = Structure.ProtoPeer.parseFrom(data);
        return fromProto(protoPeer);
    }

    // ==================== Proto转换 ====================
    /**
     * 转换为Protobuf对象
     */
    public Structure.ProtoPeer toProto() {
        Structure.ProtoPeer.Builder builder = Structure.ProtoPeer.newBuilder();
        // 核心标识字段
        if (id != null) {
            builder.setId(ByteString.copyFrom(id));
        }
        if (privateKey != null) {
            builder.setPrivateKey(ByteString.copyFrom(privateKey));
        }
        // 网络信息字段
        if (address != null) {
            builder.setAddress(address);
        }
        builder.setPort(port);
        if (multiaddr != null) {
            builder.setMultiaddr(multiaddr);
        }
        if (protocolVersion != null) {
            builder.setProtocolVersion(protocolVersion);
        }

        // 节点状态字段
        builder.setNodeType(nodeType);
        builder.setIsOnline(isOnline);
        builder.setLatestSlot(latestSlot);

        // 能力与属性字段
        builder.setIsValidator(isValidator);
        builder.setStakeAmount(stakeAmount);
        builder.setSoftwareVersion(softwareVersion);
        builder.setLastSeen(lastSeen);

        if (hashHex!=null){
            builder.setLatestHash(hashHex);
        }

        builder.setHeight(height);
        builder.setTotalWork(ByteString.copyFrom(chainWork.toBytes()));
        return builder.build();
    }

    /**
     * 从Protobuf对象转换为Peer对象
     */
    public static Peer fromProto(Structure.ProtoPeer protoPeer) {
        Peer peer = new Peer();

        // 核心标识字段
        if (!protoPeer.getId().isEmpty()) {
            peer.setId(protoPeer.getId().toByteArray());
        }

        if (!protoPeer.getPrivateKey().isEmpty()) {
            peer.setPrivateKey(protoPeer.getPrivateKey().toByteArray());
        }
        // 网络信息字段
        peer.setAddress(protoPeer.getAddress());
        peer.setPort(protoPeer.getPort());
        peer.setMultiaddr(protoPeer.getMultiaddr());
        peer.setProtocolVersion(protoPeer.getProtocolVersion());

        // 节点状态字段
        peer.setNodeType(protoPeer.getNodeType());
        peer.setOnline(protoPeer.getIsOnline());
        peer.setLatestSlot(protoPeer.getLatestSlot());

        // 能力与属性字段
        peer.setValidator(protoPeer.getIsValidator());
        peer.setStakeAmount(protoPeer.getStakeAmount());
        peer.setSoftwareVersion(protoPeer.getSoftwareVersion());
        peer.setLastSeen(protoPeer.getLastSeen());



        //最新hash
        peer.setHashHex(protoPeer.getLatestHash());
        peer.setHeight(protoPeer.getHeight());
        peer.setChainWork(UInt256.fromBytes(protoPeer.getTotalWork().toByteArray()));

        // 衍生字段inetSocketAddress不序列化，调用get时自动构建
        return peer;
    }

    /**
     * 计算Peer对象序列化后的字节大小（用于内存/网络传输预估）
     */
    public int getSerializedSize() {
        return toProto().getSerializedSize();
    }
}
