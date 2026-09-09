package com.bit.coin.config;


import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.KeyInfo;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bit.coin.utils.Ed25519HDWallet.generateMnemonic;
import static com.bit.coin.utils.Ed25519HDWallet.getSolanaKeyPair;


@Slf4j
@Data
@Component
@Order(0)
@ConfigurationProperties(prefix = "system")
public class SystemConfig {
    private boolean dbSyncWrites = true;
    private long minFreeDiskBytes = 256L * 1024 * 1024;
    private int readinessMinPeers = 0;
    private int readinessMaxSyncLag = 2;
    private boolean networkingEnabled = true;
    private String p2pBindAddress = "127.0.0.1";
    private String path;//保存路径
    private String username;
    @com.fasterxml.jackson.annotation.JsonIgnore @lombok.ToString.Exclude
    private String password;
    private Integer maxSize;//最大内存占用大小 MB
    private Integer quicPort;
    private Boolean isStun;
    private Integer stunPort;
    private List<String> stunAddress;
    //引导节点
    private List<String> bootstrap;
    //矿工地址
    private List<String> minerAddressList;
    //挖矿方式
    private Integer miningType;

    //网络参数 MainNetParams TestNet3Params RegressionNetParams
    private String netParams;

    //网络参数配置
    private ParamsConfig.Params params;


    // 本地节点标识
    public static final byte[] PEER_KEY = "LOCAL_PEER".getBytes();

    // 网络参数映射
    private static final Map<String, ParamsConfig.Params> NETWORK_MAP = new HashMap<>();

    static {
        // 初始化网络映射
        NETWORK_MAP.put("MainNetParams", ParamsConfig.MainNetParams);
        NETWORK_MAP.put("TestNet3Params", ParamsConfig.TestNet3Params);
        NETWORK_MAP.put("RegressionNetParams", ParamsConfig.RegressionNetParams);
    }


    //节点信息
    @Getter
    public static Peer SelfPeer;



    @Autowired
    private DataBase dataBase;


    public static final DateTimeFormatter TX_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai")); // 核心修改：上海时区

    @PostConstruct
    public void init() {
        initializeNetworkParams();
        if (minFreeDiskBytes < 0 || readinessMinPeers < 0 || readinessMinPeers > 100 || readinessMaxSyncLag < 0)
            throw new IllegalArgumentException("Invalid readiness or storage thresholds");
        if (quicPort == null || quicPort < 1 || quicPort > 65535 || path == null || path.isBlank())
            throw new IllegalArgumentException("Invalid node port or data directory");
        log.info("端口{}",quicPort);
        log.info("系统数据路径:{}",path);

        boolean database = dataBase.createDatabase(this);
        if (!database) {
            throw new RuntimeException("数据库创建失败");
        }

        byte[] peerData = dataBase.get(TableEnum.PEER, PEER_KEY);
        if (peerData == null) {
            SelfPeer = new Peer();
            SelfPeer.setAddress("127.0.0.1");
            SelfPeer.setPort(getQuicPort());
            var keys = Ed25519Signer.generateKeyPair();
            byte[] alicePrivateKey = Ed25519Signer.extractPrivateKeyCore(keys.getPrivate());
            byte[] alicePublicKey = Ed25519Signer.extractPublicKeyCore(keys.getPublic());
            SelfPeer.setId(alicePublicKey);
            SelfPeer.setPrivateKey(alicePrivateKey);
            //保存到本地数据库
            byte[] serialize = SelfPeer.serializeLocal();
            if (serialize==null){
                //报异常
                throw new RuntimeException("节点信息序列化失败");
            }
            persistIdentity(serialize);
        } else {
            //反序列化
            SelfPeer = Peer.deserialize(peerData);
            if (SelfPeer!=null){
                SelfPeer.setPort(getQuicPort());
                if (SelfPeer.getPrivateKey() == null || SelfPeer.getPrivateKey().length != 32
                        || !java.util.Arrays.equals(SelfPeer.getId(), Ed25519Signer.derivePublicKeyFromPrivateKey(SelfPeer.getPrivateKey())))
                    throw new IllegalStateException("Stored node identity is invalid");
                persistIdentity(SelfPeer.serializeLocal());
            }else {
                //报异常
                throw new RuntimeException("节点信息反序列化失败");
            }
        }
        log.info("本地节点地址: {}",SelfPeer.getMultiaddr());

    }


    private void persistIdentity(byte[] encoded) {
        var operation = new com.bit.coin.database.rocksDb.RocksDb.DbOperation(TableEnum.PEER, PEER_KEY, encoded,
                com.bit.coin.database.rocksDb.RocksDb.DbOperation.OpType.INSERT);
        if (!dataBase.dataTransaction(java.util.List.of(operation))) throw new IllegalStateException("Cannot persist node identity");
    }

    /**
     * 根据 netParams 配置初始化网络参数
     */
    private void initializeNetworkParams() {
        if (netParams == null || netParams.isEmpty()) {
            throw new IllegalArgumentException("An explicit network configuration is required");
        } else {
            this.params = NETWORK_MAP.get(netParams);
            if (this.params == null) {
                log.error("未知的网络参数配置: {}，可选值: MainNetParams, TestNet3Params, RegressionNetParams", netParams);
                throw new IllegalArgumentException("不支持的netParams配置: " + netParams);
            }
        }
        log.info("已配置网络参数: {}", this.params.getName());
    }

    /**
     * 获取网络参数
     */
    public ParamsConfig.Params getParams() {
        if (this.params == null) {
            initializeNetworkParams();
        if (minFreeDiskBytes < 0 || readinessMinPeers < 0 || readinessMinPeers > 100 || readinessMaxSyncLag < 0)
            throw new IllegalArgumentException("Invalid readiness or storage thresholds");
        }
        return this.params;
    }

    /**
     * 快捷方法：获取当前网络的默认端口
     */
    public int getDefaultPort() {
        return Integer.parseInt(getParams().getDefaultPort());
    }

    /**
     * 检查是否是主网
     */
    public boolean isMainNet() {
        return "mainnet".equals(getParams().getName());
    }

}
