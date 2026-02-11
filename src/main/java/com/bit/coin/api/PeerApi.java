package com.bit.coin.api;

import com.bit.coin.p2p.impl.PeerClient;
import com.bit.coin.p2p.impl.PeerServiceImpl;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
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
        return routingTable.sendFindNode(hexToBytes(targetId),hexToBytes(searchId));
    }



    @RequestMapping("/findNodeMock")
    public String findNodeMock(String targetId,String searchId){
        int count = 20;
        byte[] data = new byte[36];
        //[32字节查询节点][4字节数量]
        System.arraycopy(hexToBytes(searchId), 0, data, 0, 32);
        System.arraycopy(intToBytes(count), 0, data, 32, 4);
        byte[] p2pRes = peerClient.sendData(targetId, ProtocolEnum.P2P_Find_Node_Req, data, 5000);
        return bytesToHex(p2pRes);
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
