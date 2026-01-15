package com.bit.coin.p2p.peer;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;


@Slf4j
@Data
@Component
public class Settings {
    private byte[] id;;//32字节公钥
    private byte[] privateKey;//32字节私钥
    private int bucketSize=20;
    private int findNodeSize=20;
    private int maximumLastSeenAgeToConsiderAlive;


    private int identifierSize = 256;//256位 256个桶
    private int pingScheduleTimeValue;
    private TimeUnit pingScheduleTimeUnit = TimeUnit.SECONDS;
    private int dhtExecutorPoolSize;
    private int scheduledExecutorPoolSize;
    private boolean enabledFirstStoreRequestForcePass;




}

