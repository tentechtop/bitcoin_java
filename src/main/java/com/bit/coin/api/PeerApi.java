package com.bit.coin.api;

import com.bit.coin.p2p.impl.PeerClient;
import com.bit.coin.p2p.impl.PeerServiceImpl;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.quic.QuicConnection;
import com.bit.coin.p2p.quic.QuicConnectionManager;
import com.bit.coin.utils.SerializeUtils;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.bit.coin.database.rocksDb.RocksDb.intToBytes;
import static com.bit.coin.structure.block.HexByteArraySerializer.bytesToHex;
import static com.bit.coin.structure.block.HexByteArraySerializer.hexToBytes;

@Slf4j
@RestController
@RequestMapping("/peer")
public class PeerApi {


    @Autowired
    private PeerServiceImpl peerService;

    @Autowired
    private RoutingTable routingTable;

    @Autowired
    private PeerClient peerClient;

    //获取路由表中所有的节点
    @RequestMapping("/getAllNodes")
    public List<Peer> getAllNodes(){
        return peerService.getAllNodes();
    }

    //获取在线的所有节点
    @RequestMapping("/getOnlineNodes")
    public List<Peer> getOnlineNodes(){
        return peerService.getOnlineNodes();
    }

    //查询节点 参数是base58编码的节点地址
    @RequestMapping("/queryNode")
    public Peer queryNode(String nodeId){
        return routingTable.getNodeByBase58Id(nodeId);
    }

    //迭代查询节点 本地没有就查询最近的并根据返回的数据继续查询
    @RequestMapping("/iterativeFindNode")
    public List<Peer> iterativeFindNode(String nodeId){
        return routingTable.iterativeFindNode(nodeId);
    }

    //获取路由表 且按照桶排序 返回list<list<peer>>
    @RequestMapping("/getRoutingTable")
    public List<List<Peer>> getRoutingTable(){
        return routingTable.getRoutingTable();
    }



    @RequestMapping("/findNode")
    public List<Peer> findNode(String targetId,String searchId){
        return routingTable.sendFindNode(Base58.decode(targetId),Base58.decode(searchId));
    }


    //向指定节点迭代查询
    @RequestMapping("/findNodeIterative")
    public List<Peer> findNodeIterative(String targetId,String searchId){
        return routingTable.findNodeIterative(Base58.decode(targetId),Base58.decode(searchId));
    }



    @RequestMapping("/findNodeMock")
    public List<Peer> findNodeMock(String targetId,String searchId) throws Exception {
        int count = 20;
        byte[] data = new byte[36];
        //[32字节查询节点][4字节数量]
        System.arraycopy(Base58.decode(searchId), 0, data, 0, 32);
        System.arraycopy(intToBytes(count), 0, data, 32, 4);
        byte[] p2pRes = peerClient.sendData(bytesToHex(Base58.decode(targetId)), ProtocolEnum.P2P_Find_Node_Req, data, 5000);
        P2PMessage deserialize = P2PMessage.deserialize(p2pRes);
        byte[] data1 = deserialize.getData();
        List<Peer> peers = routingTable.deserializePeers(data1);
        return peers;
    }






    @RequestMapping("/getTest")
    public Peer getTest(){
        Peer p = routingTable.getNodeByBase58Id("CxNZoe5Xsj41eGQzj5g2Njoy9qQiVS2thbHqmF1qCjeQ");
        //获取节点的连接
        QuicConnection cxNZoe5Xsj41eGQzj5g2Njoy9qQiVS2thbHqmF1qCjeQ = QuicConnectionManager.getPeerConnection(bytesToHex(Base58.decode("CxNZoe5Xsj41eGQzj5g2Njoy9qQiVS2thbHqmF1qCjeQ")));
        byte[] sharedSecret = cxNZoe5Xsj41eGQzj5g2Njoy9qQiVS2thbHqmF1qCjeQ.getSharedSecret();
        log.info("协商密钥{}",bytesToHex(sharedSecret));
        return p;
    }


}
