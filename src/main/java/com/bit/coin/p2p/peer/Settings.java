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

    private int bucketSize=20;
    private int findNodeSize=20;
    private int identifierSize = 256;//256位 256个桶 32*8






}

