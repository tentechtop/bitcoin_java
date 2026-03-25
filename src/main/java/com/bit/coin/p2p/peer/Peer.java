package com.bit.coin.p2p.peer;


import com.bit.coin.utils.HexByteArraySerializer;
import com.bit.coin.utils.MultiAddress;
import com.bit.coin.utils.UInt256;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import tools.jackson.databind.annotation.JsonSerialize;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

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
    // -------------------------- 序列化/反序列化核心实现 --------------------------
    /**
     * 序列化当前Peer对象为字节数组
     * 采用BigEndian字节序，保证跨平台兼容性
     */
    public byte[] serialize() {
        try {
            // 1. 先计算序列化总长度，初始化ByteBuffer
            int totalSize = getSerializedSize();
            ByteBuffer buffer = ByteBuffer.allocate(totalSize);
            buffer.order(java.nio.ByteOrder.BIG_ENDIAN);

            // 2. 序列化核心标识字段
            // id: int(长度) + byte[]，null则长度为-1
            serializeByteArray(buffer, id);
            // privateKey: int(长度) + byte[]
            serializeByteArray(buffer, privateKey);

            // 3. 序列化网络信息字段
            // address: int(长度) + UTF-8字节数组
            serializeString(buffer, address);
            // port: int
            buffer.putInt(port);
            // protocolVersion: int(长度) + UTF-8字节数组
            serializeString(buffer, protocolVersion);

            // 4. 序列化节点状态字段
            // nodeType: int
            buffer.putInt(nodeType);
            // isOnline: boolean (1字节，true=1, false=0)
            buffer.put(isOnline ? (byte) 1 : (byte) 0);
            // latestSlot: long
            buffer.putLong(latestSlot);

            // 5. 序列化能力与属性字段
            // isValidator: boolean
            buffer.put(isValidator ? (byte) 1 : (byte) 0);
            // stakeAmount: double
            buffer.putDouble(stakeAmount);
            // softwareVersion: int
            buffer.putInt(softwareVersion);
            // lastSeen: long
            buffer.putLong(lastSeen);

            // 6. 序列化其他字段
            // hashHex: int(长度) + UTF-8字节数组
            serializeString(buffer, hashHex);
            // height: int
            buffer.putInt(height);
            // chainWork: 固定32字节（UInt256）
            buffer.put(chainWork != null ? chainWork.toBytes() : UInt256.fromLong(0L).toBytes());

            // 7. 重置缓冲区指针，返回字节数组
            buffer.flip();
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        }catch (Exception e){
            log.error("序列化Peer对象失败",e);
            return null;
        }
    }

    /**
     * 从字节数组反序列化为Peer对象
     */
    public static Peer deserialize(byte[] data){
        try {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("反序列化数据不能为空");
            }

            // 1. 初始化ByteBuffer
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(java.nio.ByteOrder.BIG_ENDIAN);

            // 2. 构建Peer对象
            Peer peer = new Peer();

            // 3. 反序列化核心标识字段
            peer.setId(deserializeByteArray(buffer));
            peer.setPrivateKey(deserializeByteArray(buffer));

            // 4. 反序列化网络信息字段
            peer.setAddress(deserializeString(buffer));
            peer.setPort(buffer.getInt());
            peer.setProtocolVersion(deserializeString(buffer));

            // 5. 反序列化节点状态字段
            peer.setNodeType(buffer.getInt());
            peer.setOnline(buffer.get() == 1);
            peer.setLatestSlot(buffer.getLong());

            // 6. 反序列化能力与属性字段
            peer.setValidator(buffer.get() == 1);
            peer.setStakeAmount(buffer.getDouble());
            peer.setSoftwareVersion(buffer.getInt());
            peer.setLastSeen(buffer.getLong());

            // 7. 反序列化其他字段
            peer.setHashHex(deserializeString(buffer));
            peer.setHeight(buffer.getInt());
            // 反序列化UInt256（固定32字节）
            byte[] chainWorkBytes = new byte[32];
            buffer.get(chainWorkBytes);
            peer.setChainWork(UInt256.fromBytes(chainWorkBytes));

            // 8. 衍生字段初始化（inetSocketAddress由address+port构建）
            if (peer.getAddress() != null && peer.getPort() > 0) {
                peer.setInetSocketAddress(new InetSocketAddress(peer.getAddress(), peer.getPort()));
            }

            return peer;
        }catch (Exception e){
            log.error("反序列化Peer对象失败",e);
            return null;
        }
    }

    /**
     * 计算Peer对象序列化后的字节大小（用于内存/网络传输预估）
     */
    public int getSerializedSize() {
        int size = 0;

        // 核心标识字段：byte[] = int(4字节) + 数组长度
        size += 4 + (id != null ? id.length : 0);
        size += 4 + (privateKey != null ? privateKey.length : 0);

        // 网络信息字段：String = int(4字节) + 字节长度；int=4字节
        size += 4 + (address != null ? address.getBytes(StandardCharsets.UTF_8).length : 0);
        size += 4; // port (int)
        size += 4 + (protocolVersion != null ? protocolVersion.getBytes(StandardCharsets.UTF_8).length : 0);

        // 节点状态字段：int=4, boolean=1, long=8
        size += 4; // nodeType
        size += 1; // isOnline
        size += 8; // latestSlot

        // 能力与属性字段：boolean=1, double=8, int=4, long=8
        size += 1; // isValidator
        size += 8; // stakeAmount
        size += 4; // softwareVersion
        size += 8; // lastSeen

        // 其他字段：String=4+长度, int=4, UInt256=32
        size += 4 + (hashHex != null ? hashHex.getBytes(StandardCharsets.UTF_8).length : 0);
        size += 4; // height
        size += 32; // chainWork (UInt256固定32字节)

        return size;
    }

    // -------------------------- 私有辅助方法 --------------------------
    /**
     * 辅助方法：序列化byte[]到ByteBuffer（长度+字节数组）
     * @param buffer ByteBuffer
     * @param array  要序列化的字节数组（null则长度写-1）
     */
    private void serializeByteArray(ByteBuffer buffer, byte[] array) {
        if (array == null) {
            buffer.putInt(-1);
            return;
        }
        buffer.putInt(array.length);
        buffer.put(array);
    }

    /**
     * 辅助方法：序列化String到ByteBuffer（长度+UTF-8字节数组）
     * @param buffer ByteBuffer
     * @param str    要序列化的字符串（null则长度写-1）
     */
    private void serializeString(ByteBuffer buffer, String str) {
        if (str == null) {
            buffer.putInt(-1);
            return;
        }
        byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(strBytes.length);
        buffer.put(strBytes);
    }

    /**
     * 辅助方法：从ByteBuffer反序列化byte[]
     */
    private static byte[] deserializeByteArray(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length == -1) {
            return null;
        }
        byte[] array = new byte[length];
        buffer.get(array);
        return array;
    }

    /**
     * 辅助方法：从ByteBuffer反序列化String
     */
    private static String deserializeString(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length == -1) {
            return null;
        }
        byte[] strBytes = new byte[length];
        buffer.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
    }

}
