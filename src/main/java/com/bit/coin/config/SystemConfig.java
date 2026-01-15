package com.bit.coin.config;

import com.bit.coin.database.DataBase;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    //网络参数 MainNetParams TestNet3Params RegressionNetParams
    private String netParams;

    //矿工地址



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
