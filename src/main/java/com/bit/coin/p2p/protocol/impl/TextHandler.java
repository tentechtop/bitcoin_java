package com.bit.coin.p2p.protocol.impl;


import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class TextHandler implements ProtocolHandler.VoidProtocolHandler{


    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        log.info("数据长度{}",requestParams.getData().length);

        String text = "我已经收到回复";
    }
}
