package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.config.CommonConfig;
import com.bit.coin.p2p.protocol.NetworkHandshake;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;

import com.bit.coin.utils.ECCWithAESGCM;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.bit.coin.config.CommonConfig.*;
import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;
import static com.bit.coin.utils.ECCWithAESGCM.generateCurve25519KeyPair;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;


@Slf4j
@Component
public class NetworkHandshakeHandler implements ProtocolHandler.ResultProtocolHandler{

    @Autowired
    private CommonConfig commonConfig;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        byte[] senderId = requestParams.getSenderId();
        byte[] data = requestParams.getData();

        NetworkHandshake deserialize = NetworkHandshake.deserialize(data);
        byte[] aPublicKey = deserialize.getSharedSecret();

        byte[][] BKeys = generateCurve25519KeyPair();
        byte[] bPrivateKey = BKeys[0];
        byte[] bPublicKey = BKeys[1];

        //协商
        byte[] sharedSecret = ECCWithAESGCM.generateSharedSecret(bPrivateKey, aPublicKey);
        log.info("协议 共享加密密钥对sharedSecret: {}", bytesToHex(sharedSecret));

        NetworkHandshake networkHandshake = new NetworkHandshake();
        networkHandshake.setNodeId(getSelf().getId());
        networkHandshake.setSharedSecret(bPublicKey);

        byte[] serialize = networkHandshake.serialize();
        //构建协议的响应
        P2PMessage p2PMessage = newResponseMessage(CommonConfig.getSelf().getId(), ProtocolEnum.Network_handshake_V1,requestParams.getRequestId(), serialize);
        return p2PMessage.serialize();
    }
}
