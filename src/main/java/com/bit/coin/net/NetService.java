package com.bit.coin.net;

import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.structure.block.Block;
import com.bit.coin.utils.UInt256;

import java.util.List;

public interface NetService {

    SyncProgress startSyncBlock();

    SyncProgress startSyncBlockHeadersFirst();

    SyncProgress getSyncProgress();

    List<SyncPeerState> getSyncPeerStates();

    int getCurrentLocalHeight();

    UInt256 getCurrentLocalChainWork();

    int getBestNetworkHeight();

    Peer getBestPeer();

    List<Peer> getConnectedPeers();

    Block getLatestBlockFromOnlinePeers();
}
