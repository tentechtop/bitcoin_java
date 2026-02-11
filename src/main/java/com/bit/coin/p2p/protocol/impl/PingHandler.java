package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.config.CommonConfig;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.p2p.quic.QuicMsg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;
import static com.bit.coin.p2p.quic.QuicConstants.pushCompleteMsg;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Slf4j
@Component
public class PingHandler implements ProtocolHandler.ResultProtocolHandler{
    @Autowired
    private CommonConfig commonConfig;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        log.info("收到ping");
        byte[] senderId = requestParams.getSenderId();
        P2PMessage p2PMessage = newResponseMessage(CommonConfig.getSelf().getId(), ProtocolEnum.PING_V1,requestParams.getRequestId(), new byte[]{0x02});
        return p2PMessage.serialize();
    }
}
