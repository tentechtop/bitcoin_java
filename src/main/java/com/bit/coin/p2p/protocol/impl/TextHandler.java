package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.config.CommonConfig;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;

@Slf4j
@Component
public class TextHandler implements ProtocolHandler.ResultProtocolHandler{

    @Autowired
    private CommonConfig commonConfig;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        log.info("数据长度{}",requestParams.getData().length);

        String text = "我已经收到回复";
        P2PMessage p2PMessage = newResponseMessage(CommonConfig.getSelf().getId(), ProtocolEnum.TEXT_V1,requestParams.getRequestId(), text.getBytes(StandardCharsets.UTF_8));

        return p2PMessage.serialize();
    }
}
