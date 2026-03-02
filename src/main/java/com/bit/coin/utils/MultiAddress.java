package com.bit.coin.utils;

import lombok.Data;
import org.bitcoinj.core.Base58;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

/**
 * 自解释型P2P多地址解析工具类（单类整合版）
 * 支持格式：/ip4/{IPv4}/tcp/{端口}/p2p/{节点ID}
 * 支持协议：TCP、UDP、QUIC | 支持IP类型：IPv4/IPv6
 * 节点ID规则：Base58编码的32字节公钥（长度为52位的Base58字符串）
 * 使用方式：直接实例化或通过静态方法构建
 */
@Data
public class MultiAddress {
    // 静态常量定义
    private static final String SEGMENT_SEPARATOR = "/";
    private static final Pattern IP4_PATTERN = Pattern.compile("^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");
    private static final Pattern IP6_PATTERN = Pattern.compile("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$");

    // 传输协议枚举（内置）
    public enum Protocol {
        TCP, UDP, QUIC
    }

    // 核心字段
    private String ipType;       // ip4/ip6
    private String ipAddress;    // IP地址
    private Protocol protocol;   // 传输协议
    private int port;            // 端口号
    private String peerId;       // P2P节点ID（Base58编码32字节公钥）
    private String rawAddress;   // 原始地址字符串
    private byte[] peerIdBytes;
    private String peerIdHex;


    // ======================== 构造方法 ========================
    /**
     * 空构造（用于手动构建地址）
     */
    public MultiAddress() {}

    /**
     * 解析构造（传入原始地址自动解析）
     * @param rawAddress 原始多地址字符串
     */
    public MultiAddress(String rawAddress) {
        this.rawAddress = rawAddress;
        parseInternal(rawAddress);
    }

    // ======================== 核心解析方法 ========================
    /**
     * 内部解析逻辑（封装校验和字段赋值）
     */
    private void parseInternal(String rawAddress) {
        // 空值校验
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new IllegalArgumentException("原始地址不能为空");
        }

        // 分割地址段并校验长度
        String[] segments = rawAddress.split(SEGMENT_SEPARATOR);
        if (segments.length != 7) {
            throw new IllegalArgumentException(
                    "地址格式错误，合法示例：/ip4/101.35.87.31/tcp/5002/p2p/Base58编码的32字节公钥");
        }

        // 1. 解析IP类型
        String ipType = segments[1];
        if (!"ip4".equals(ipType) && !"ip6".equals(ipType)) {
            throw new IllegalArgumentException("不支持的IP类型：" + ipType + "（仅支持ip4/ip6）");
        }
        this.ipType = ipType;

        // 2. 解析并校验IP地址
        String ipAddress = segments[2];
        if (!isValidIp(ipAddress, ipType)) {
            throw new IllegalArgumentException("非法" + ipType + "地址：" + ipAddress);
        }
        this.ipAddress = ipAddress;

        // 3. 解析传输协议
        String protocolStr = segments[3].toUpperCase();
        try {
            this.protocol = Protocol.valueOf(protocolStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的协议：" + protocolStr + "（仅支持TCP/UDP/QUIC）");
        }

        // 4. 解析并校验端口
        String portStr = segments[4];
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口必须为数字：" + portStr);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口超出范围（1-65535）：" + port);
        }
        this.port = port;

        // 5. 校验P2P标识
        if (!"p2p".equals(segments[5])) {
            throw new IllegalArgumentException("地址缺少p2p标识，格式错误");
        }

        // 6. 解析并校验节点ID（修正：Base58编码32字节公钥，长度52位）
        String peerId = segments[6];
        //base58解码后 是32字节
        byte[] decode = Base58.decode(peerId);
        if (decode.length != 32) {
            throw new IllegalArgumentException("节点ID长度错误，请检查");
        }
        this.peerId = peerId;
        this.peerIdBytes = decode;//32字节
        this.peerIdHex = bytesToHex(decode);//32字节
    }

    // ======================== 静态构建方法（快捷创建） ========================
    /**
     * 静态解析方法（替代new，语义更清晰）
     * @param rawAddress 原始地址
     * @return MultiAddress实例
     */
    public static MultiAddress parse(String rawAddress) {
        return new MultiAddress(rawAddress);
    }

    /**
     * 手动构建MultiAddress实例
     * @param ipType IP类型（ip4/ip6）
     * @param ipAddress IP地址
     * @param protocol 传输协议
     * @param port 端口
     * @param peerId 节点ID（Base58编码32字节公钥）
     * @return MultiAddress实例
     */
    public static MultiAddress build(String ipType, String ipAddress, Protocol protocol, int port, String peerId) {
        MultiAddress multiAddress = new MultiAddress();
        // 前置校验
        if (!"ip4".equals(ipType) && !"ip6".equals(ipType)) {
            throw new IllegalArgumentException("IP类型仅支持ip4/ip6");
        }
        if (!isValidIp(ipAddress, ipType)) {
            throw new IllegalArgumentException("非法" + ipType + "地址：" + ipAddress);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口超出范围（1-65535）：" + port);
        }
        //节点ID解码后是32字节公钥
        byte[] decode = Base58.decode(peerId);
        if (decode.length != 32) {
            throw new IllegalArgumentException("节点ID长度错误，请检查");
        }
        // 赋值
        multiAddress.ipType = ipType;
        multiAddress.ipAddress = ipAddress;
        multiAddress.protocol = protocol;
        multiAddress.port = port;
        multiAddress.peerId = peerId;
        // 生成原始地址
        multiAddress.rawAddress = multiAddress.toRawAddress();

        multiAddress.peerIdHex = bytesToHex(decode);
        multiAddress.peerIdBytes = decode;
        return multiAddress;
    }

    // ======================== 工具方法 ========================
    /**
     * 校验IP地址合法性
     */
    private static boolean isValidIp(String ipAddress, String ipType) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            return "ip4".equals(ipType)
                    ? inetAddress instanceof Inet4Address
                    : inetAddress instanceof Inet6Address;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * 转换为原始多地址字符串
     * @return 标准格式的多地址字符串
     */
    public String toRawAddress() {
        return String.format("/%s/%s/%s/%d/p2p/%s",
                this.ipType,
                this.ipAddress,
                this.protocol.name().toLowerCase(),
                this.port,
                this.peerId);
    }

    // ======================== Getter & Setter ========================
    public String getIpType() {
        return ipType;
    }

    public void setIpType(String ipType) {
        this.ipType = ipType;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getRawAddress() {
        return rawAddress;
    }

    // ======================== 重写toString ========================
    @Override
    public String toString() {
        return "MultiAddress{" +
                "ipType='" + ipType + '\'' +
                ", ipAddress='" + ipAddress + '\'' +
                ", protocol=" + protocol +
                ", port=" + port +
                ", peerId='" + peerId + '\'' +
                ", rawAddress='" + rawAddress + '\'' +
                '}';
    }

    // ======================== 测试示例 ========================
    public static void main(String[] args) {
        // 方式1：通过原始地址解析（任意Base58编码32字节公钥）
        String rawAddr = "/ip4/101.35.87.31/tcp/5002/p2p/B3ouHNKzVqwd6U15KTM2patf5ChJdYhwi3oPVqvr6tPf";
        MultiAddress addr1 = new MultiAddress(rawAddr);
        System.out.println("解析结果：" + addr1);
        System.out.println("原始地址还原：" + addr1.toRawAddress());

        // 方式2：静态parse方法（测试不同节点ID）
        String udpAddr = "/ip4/192.168.1.1/udp/8080/p2p/B3ouHNKzVqwd6U15KTM2patf5ChJdYhwi3oPVqvr6tPf";
        MultiAddress addr2 = MultiAddress.parse(udpAddr);
        System.out.println("\nUDP地址解析：" + addr2);
        System.out.println("协议类型：" + addr2.getProtocol());

        // 方式3：手动构建地址
        MultiAddress addr3 = MultiAddress.build(
                "ip4",
                "10.0.0.1",
                Protocol.QUIC,
                9090,
                "B3ouHNKzVqwd6U15KTM2patf5ChJdYhwi3oPVqvr6tPf"
        );
        System.out.println("\n手动构建地址：" + addr3);
        System.out.println("构建后的原始地址：" + addr3.toRawAddress());
    }
}