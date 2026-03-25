package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.bit.coin.config.SystemConfig.SelfPeer;
import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;


@Slf4j
@Component
public class PingHandler implements ProtocolHandler.ResultProtocolHandler{


    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        log.info("收到ping");
        byte[] senderId = requestParams.getSenderId();
        P2PMessage p2PMessage = newResponseMessage(SelfPeer.getId(), ProtocolEnum.PING_V1,requestParams.getRequestId(), new byte[]{0x02});
        return p2PMessage.serialize();
    }
}
