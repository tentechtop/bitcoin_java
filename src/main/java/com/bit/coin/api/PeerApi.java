package com.bit.coin.api;

import com.bit.coin.p2p.impl.PeerServiceImpl;
import com.bit.coin.p2p.peer.Peer;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/peer")
public class PeerApi {


    @Autowired
    private PeerServiceImpl peerService;

    //获取路由表中所有的节点
    @RequestMapping("/getAllNodes")
    public List<Peer> getAllNodes(){
        return peerService.getAllNodes();
    }

}
