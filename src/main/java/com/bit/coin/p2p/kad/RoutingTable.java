package com.bit.coin.p2p.kad;

import com.bit.coin.config.CommonConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.structure.key.KeyInfo;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.MultiAddress;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.bit.coin.config.CommonConfig.PEER_KEY;
import static com.bit.coin.config.CommonConfig.self;
import static com.bit.coin.utils.Ed25519HDWallet.generateMnemonic;
import static com.bit.coin.utils.Ed25519HDWallet.getSolanaKeyPair;

@Slf4j
@NoArgsConstructor
@Component
@Data
public class RoutingTable {

    @Autowired
    private SystemConfig config;

    /* 路由表所有者的ID（节点ID） */
    private byte[] localNodeId;

    /* 存储桶列表 */
    private CopyOnWriteArrayList<Bucket> buckets;//读多写少场景

    // 最后访问时间，用于刷新桶
    private final Map<Integer, Long> lastBucketAccessTime = new ConcurrentHashMap<>();

    private DataBase dataBase;

    /**
     * Spring初始化方法（替代构造方法）
     * 当依赖注入完成后，Spring会自动调用此方法
     */
    @PostConstruct
    public void init() throws Exception{
        dataBase = config.getDataBase();

        byte[] bytes = dataBase.get(TableEnum.PEER, PEER_KEY);
        if (bytes == null) {
            self = new Peer();
            self.setAddress("127.0.0.1");
            self.setPort(config.getQuicPort());

            List<String> mnemonic = generateMnemonic();
            KeyInfo baseKey = getSolanaKeyPair(mnemonic, 0, 0);

            byte[] alicePrivateKey = baseKey.getPrivateKey();
            byte[] alicePublicKey =  Ed25519Signer.derivePublicKeyFromPrivateKey(alicePrivateKey);

            self.setId(alicePublicKey);
            self.setPrivateKey(alicePrivateKey);

            //保存到本地数据库
            byte[] serialize = self.serialize();
            dataBase.insert(TableEnum.PEER, PEER_KEY, serialize);
        } else {
            //反序列化
            self = Peer.deserialize(bytes);
            self.setPort(config.getQuicPort());
            dataBase.update(TableEnum.PEER, PEER_KEY, self.serialize());
        }
        byte[] selfNodeId = self.getId();
        log.info("本地节点初始化完成，ID: {}, 监听端口: {}", Base58.encode(selfNodeId), self.getPort());
        log.info("Base58.encode(selfNodeId){}",Base58.encode(selfNodeId).length());

        MultiAddress address = MultiAddress.build(
                "ip4",
                "127.0.0.1",
                MultiAddress.Protocol.QUIC,
                self.getPort(),
                Base58.encode(self.getId())
        );
        log.info("地址: {}" ,address.toRawAddress());
        log.info("地址: {}" ,address.getPeerIdHex());

        localNodeId = selfNodeId;
        buckets = new CopyOnWriteArrayList<>();
        for (int i = 0; i < localNodeId.length + 1; i++) {
            buckets.add(createBucketOfId(i));
        }
    }

    private Bucket createBucketOfId(int id) {
        return new Bucket(id);
    }


    /**
     * 添加一个节点到路由表  如果满了就删除一个最后访问的
     */

    /**
     * 找到与这个ID最近的一个节点 根据节点类型
     */

    /**
     * 找到与这个ID最近的20个节点 根据节点类型
     */

    /**
     * 计算两个节点之间的距离
     */


    /**
     * 获取 K桶
     */
    public List<Bucket> getBuckets() {
        return buckets;
    }


    /**
     * 删除节点
     */
    public void delete(Peer peer) {

    }

    public Peer getNode(String nodeId) {
        //不为空 且解码正确
        return getNode(Base58.decode(nodeId));
    }
    public Peer getNode(byte[] nodeId) {
        //不为空
        return null;
    }

    /**
     * 是否包含节点 参数节点ID
     */

    /**
     * 恢复方法  从节点列表一个一个重新加载到路由表
     */

    /**
     * 持久化
     */

}
