package com.bit.coin.p2p.protocol.impl;


import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import static com.bit.coin.config.SystemConfig.SelfPeer;
import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;

@Slf4j
@Component
public class Text2Handler2 implements ProtocolHandler.ResultProtocolHandler{


    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        log.debug("数据长度{}",requestParams.getData().length);

        String text = "我已经收到回复";
        P2PMessage p2PMessage = newResponseMessage(SelfPeer.getId(), ProtocolEnum.TEXT_V2,requestParams.getRequestId(), text.getBytes(StandardCharsets.UTF_8));
        return p2PMessage.serialize();
    }
}
