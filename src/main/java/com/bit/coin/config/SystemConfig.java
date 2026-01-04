package com.bit.coin.config;

import com.bit.coin.database.DataBase;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

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


    @Autowired
    private DataBase dataBase;


    @PostConstruct
    public void init() {
        log.info("端口{}",quicPort);
        log.info("系统数据路径:{}",path);
        boolean database = dataBase.createDatabase(this);
        if (!database) {
            throw new RuntimeException("数据库创建失败");
        }
    }



}
