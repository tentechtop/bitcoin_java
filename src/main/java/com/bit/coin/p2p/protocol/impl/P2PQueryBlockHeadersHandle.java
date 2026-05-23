package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.p2p.protocol.message.BlockHeadersPayload;
import com.bit.coin.structure.block.BlockHeader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.bit.coin.config.SystemConfig.SelfPeer;
import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;

@Slf4j
@Component
public class P2PQueryBlockHeadersHandle implements ProtocolHandler.ResultProtocolHandler {

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        BlockHeadersPayload.Request request = BlockHeadersPayload.deserializeRequest(requestParams.getData());
        int startHeight = Math.max(0, request.startHeight());
        int stopHeight = Math.max(startHeight, request.stopHeight());
        int maxCount = Math.min(request.maxCount(), BlockHeadersPayload.MAX_HEADERS_PER_RESPONSE);

        List<BlockHeadersPayload.Entry> entries = new ArrayList<>(maxCount);
        for (int height = startHeight; height <= stopHeight && entries.size() < maxCount; height++) {
            BlockHeader header = blockChainService.getBlockHeaderByHeight(height);
            if (header == null) {
                break;
            }
            entries.add(new BlockHeadersPayload.Entry(height, header));
        }

        byte[] payload = BlockHeadersPayload.serializeResponse(entries);
        P2PMessage response = newResponseMessage(
                SelfPeer.getId(),
                ProtocolEnum.P2P_Query_Block_Headers,
                requestParams.getRequestId(),
                payload
        );
        return response.serialize();
    }
}
