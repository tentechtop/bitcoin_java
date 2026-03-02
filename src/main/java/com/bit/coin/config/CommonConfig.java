package com.bit.coin.config;



import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.quic.QuicMsg;
import com.bit.coin.structure.key.KeyInfo;
import com.bit.coin.utils.Ed25519Signer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.utils.Ed25519HDWallet.generateMnemonic;
import static com.bit.coin.utils.Ed25519HDWallet.getSolanaKeyPair;


@Slf4j
@Component
public class CommonConfig {

    //节点信息
    @Getter
    public static Peer self;//本地节点信息  最好通过注入的方法使用

    @Autowired
    private SystemConfig config;

    @PostConstruct
    public void init() throws Exception {
        DataBase dataBase = config.getDataBase();
    }

    // 本地节点标识
    public static final byte[] PEER_KEY = "LOCAL_PEER".getBytes();


    /**
     * 请求响应Future缓存：最大容量100万个，30秒过期（请求超时后自动清理，避免内存泄漏）
     * Key：请求ID，Value：响应Future
     * 16字节的UUIDV7 - > CompletableFuture<byte[]>
     */
    public static Cache<String, CompletableFuture<QuicMsg>> RESPONSE_FUTURECACHE  = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .weakValues() // 弱引用存储Future，GC时可回收
            .recordStats()
            .build();


    //已经存在的资源 hashHex -> 资源类型
    public static Cache<String, Integer> EXIST_RESOURCE_CACHE  = Caffeine.newBuilder()
            .maximumSize(1000_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .recordStats()
            .build();




    @PreDestroy
    public void shutdown() throws InterruptedException {
        log.info("关闭全局调度器");
        RESPONSE_FUTURECACHE.invalidateAll();
    }


}
