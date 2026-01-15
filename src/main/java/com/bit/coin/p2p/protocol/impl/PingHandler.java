package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.config.CommonConfig;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;

@Slf4j
@Component
public class PingHandler implements ProtocolHandler.ResultProtocolHandler{

    @Autowired
    private CommonConfig commonConfig;
    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        log.info("收到ping");
        P2PMessage p2PMessage = newResponseMessage(commonConfig.getSelf().getId(), ProtocolEnum.Network_handshake_V1,requestParams.getRequestId(), new byte[]{0x02});
        return p2PMessage.serialize();
    }
}
