package com.bit.coin.config;

import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bit.coin.utils.ECCWithAESGCM.generateCurve25519KeyPair;

@Slf4j
@Data
@Component
@Order(0)
@ConfigurationProperties(prefix = "system")
public class SystemConfig {
    private String path;//保存路径
    private String username;
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


    public static final byte[] PEER_SECURITY_KEY = "peer_security_key".getBytes();
    public static byte[][] SelfKey = null;


    //网络参数配置
    private ParamsConfig.Params params;

    // 网络参数映射
    private static final Map<String, ParamsConfig.Params> NETWORK_MAP = new HashMap<>();

    static {
        // 初始化网络映射
        NETWORK_MAP.put("MainNetParams", ParamsConfig.MainNetParams);
        NETWORK_MAP.put("TestNet3Params", ParamsConfig.TestNet3Params);
        NETWORK_MAP.put("RegressionNetParams", ParamsConfig.RegressionNetParams);
    }


    @Autowired
    private DataBase dataBase;


    @PostConstruct
    public void init() {
        log.info("端口{}",quicPort);
        log.info("系统数据路径:{}",path);
        // 初始化网络参数
        initializeNetworkParams();

        boolean database = dataBase.createDatabase(this);
        if (!database) {
            throw new RuntimeException("数据库创建失败");
        }
        byte[] key = dataBase.get(TableEnum.PEER, PEER_SECURITY_KEY);
        if (key==null){
            log.info("key是空的");
            byte[][] bytes = generateCurve25519KeyPair();
            byte[] privateKey = bytes[0];
            byte[] publicKey = bytes[1];
            //拼接成一个 byte[]
            byte[] saveBytes = new byte[64];
            System.arraycopy(privateKey, 0, saveBytes, 0, 32);
            System.arraycopy(publicKey, 0, saveBytes, 32, 32);
            SelfKey=bytes;
            //保存
            dataBase.insert(TableEnum.PEER, PEER_SECURITY_KEY, saveBytes);
        }else {
            log.info("key不为空");
            //切割 32|32
            //取前32字节
            byte[] privateKey = Arrays.copyOfRange(key, 0, 32);
            //取后32字节
            byte[] publicKey = Arrays.copyOfRange(key, 32, 64);
            //组合成byte[][]
            SelfKey = new byte[][]{privateKey, publicKey};
        }
    }

    /**
     * 根据 netParams 配置初始化网络参数
     */
    private void initializeNetworkParams() {
        if (netParams == null || netParams.isEmpty()) {
            log.warn("未配置网络参数，默认使用主网");
            this.params = ParamsConfig.MainNetParams;
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
