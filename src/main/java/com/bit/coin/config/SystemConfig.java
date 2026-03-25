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

import java.util.List;

import static com.bit.coin.utils.Ed25519HDWallet.generateMnemonic;
import static com.bit.coin.utils.Ed25519HDWallet.getSolanaKeyPair;


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




    // 本地节点标识
    public static final byte[] PEER_KEY = "LOCAL_PEER".getBytes();



    //节点信息
    @Getter
    public static Peer SelfPeer;



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

        byte[] peerData = dataBase.get(TableEnum.PEER, PEER_KEY);
        if (peerData == null) {
            SelfPeer = new Peer();
            SelfPeer.setAddress("127.0.0.1");
            SelfPeer.setPort(getQuicPort());
            List<String> mnemonic = generateMnemonic();
            KeyInfo baseKey = getSolanaKeyPair(mnemonic, 0, 0);
            byte[] alicePrivateKey = baseKey.getPrivateKey();
            byte[] alicePublicKey =  Ed25519Signer.derivePublicKeyFromPrivateKey(alicePrivateKey);
            SelfPeer.setId(alicePublicKey);
            SelfPeer.setPrivateKey(alicePrivateKey);
            //保存到本地数据库
            byte[] serialize = SelfPeer.serialize();
            if (serialize==null){
                //报异常
                throw new RuntimeException("节点信息序列化失败");
            }
            dataBase.insert(TableEnum.PEER, PEER_KEY, serialize);
        } else {
            //反序列化
            SelfPeer = Peer.deserialize(peerData);
            if (SelfPeer!=null){
                SelfPeer.setPort(getQuicPort());
                dataBase.update(TableEnum.PEER, PEER_KEY, SelfPeer.serialize());
            }else {
                //报异常
                throw new RuntimeException("节点信息反序列化失败");
            }
        }
        log.info("本地节点地址: {}",SelfPeer.getMultiaddr());

    }



}
