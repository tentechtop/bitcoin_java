package com.bit.coin.api;

import com.bit.coin.p2p.netty.PeerServiceImpl;
import com.bit.coin.p2p.kad.CandidatePeerSnapshot;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.kad.RoutingTableHealthSnapshot;
import com.bit.coin.p2p.kad.PeerScoreSnapshot;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.conn.QuicConnection;
import com.bit.coin.p2p.conn.QuicConnectionManager;
import com.bit.coin.p2p.conn.QuicConnectionStats;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.base.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.bit.coin.database.rocksDb.RocksDb.intToBytes;
import static com.bit.coin.p2p.conn.QuicConnectionManager.sendRequest;
import static com.bit.coin.utils.HexByteArraySerializer.bytesToHex;

@Slf4j
@RestController
@RequestMapping("/peer")
public class PeerApi {

    @Autowired
    private PeerServiceImpl peerService;

    @Autowired
    private RoutingTable routingTable;

    //获取路由表中所有的节点
    @GetMapping("/getAllNodes")
    public List<Peer> getAllNodes() {
        return peerService.getAllNodes();
    }

    //获取在线的所有节点
    @GetMapping("/getOnlineNodes")
    public List<Peer> getOnlineNodes() {
        return peerService.getOnlineNodes();
    }

    //查询节点 参数是base58编码的节点地址
    @GetMapping("/getConnectionStats")
    public List<QuicConnectionStats> getConnectionStats() {
        return QuicConnectionManager.getConnectionStats();
    }

    @PostMapping("/queryNode")
    public Peer queryNode(String nodeId) {
        return routingTable.getNodeByBase58Id(nodeId);
    }

    //迭代查询节点 本地没有就查询最近的并根据返回的数据继续查询
    @GetMapping("/iterativeFindNode")
    public List<Peer> iterativeFindNode(String nodeId) {
        return routingTable.iterativeFindNode(nodeId);
    }

    //获取路由表 且按照桶排序 返回list<list<peer>>
    @GetMapping("/getRoutingTable")
    public List<List<Peer>> getRoutingTable() {
        return routingTable.getRoutingTable();
    }

    @GetMapping("/dht/health")
    public RoutingTableHealthSnapshot getDhtHealth() {
        return routingTable.getHealthSnapshot();
    }

    @GetMapping("/dht/coverage")
    public Map<Integer, Integer> getDhtCoverage() {
        return routingTable.getBucketCoverage();
    }

    @GetMapping("/dht/candidates")
    public List<CandidatePeerSnapshot> getDhtCandidates() {
        return routingTable.getCandidatePeerSnapshots();
    }

    @GetMapping("/dht/scores")
    public List<PeerScoreSnapshot> getDhtScores() {
        return routingTable.getPeerScoreSnapshots();
    }

    @PostMapping("/dht/refresh")
    public String refreshDhtNow() {
        routingTable.triggerRefreshNow();
        return "ok";
    }

    @PostMapping("/dht/shareHints")
    public String shareDhtHintsNow() {
        routingTable.triggerPeerHintShareNow();
        return "ok";
    }

    @PostMapping("/findNode")
    public List<Peer> findNode(String targetId, String searchId) {
        return routingTable.sendFindNode(Base58.decode(targetId), Base58.decode(searchId));
    }

    //向指定节点迭代查询
    @PostMapping("/findNodeIterative")
    public List<Peer> findNodeIterative(String targetId, String searchId) {
        return routingTable.findNodeIterative(Base58.decode(targetId), Base58.decode(searchId));
    }

    @PostMapping("/findNodeMock")
    public List<Peer> findNodeMock(String targetId, String searchId) throws Exception {
        int count = 20;
        byte[] data = new byte[36];
        //[32字节查询节点][4字节数量]
        System.arraycopy(Base58.decode(searchId), 0, data, 0, 32);
        System.arraycopy(intToBytes(count), 0, data, 32, 4);
        P2PMessage response = sendRequest(bytesToHex(Base58.decode(targetId)), ProtocolEnum.P2P_Find_Node_Req, data,
                5000);
        if (response == null) {
            throw new IllegalArgumentException("Peer lookup returned no response");
        }
        return routingTable.deserializePeers(response.getData());
    }

}
