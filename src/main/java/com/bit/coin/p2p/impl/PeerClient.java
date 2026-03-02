package com.bit.coin.p2p.impl;

import com.bit.coin.config.CommonConfig;
import com.bit.coin.exception.SendDataEx;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.quic.QuicConnection;
import com.bit.coin.p2p.quic.QuicMsg;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static com.bit.coin.config.CommonConfig.RESPONSE_FUTURECACHE;
import static com.bit.coin.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.coin.p2p.quic.QuicConnectionManager.getPeerConnection;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;


@Slf4j
@Component
public class PeerClient {

    @Autowired
    private CommonConfig commonConfig;

    // 全局锁（避免重复连接）
    private final ReentrantLock connectLock = new ReentrantLock();


    private NioEventLoopGroup eventLoopGroup;


    private Bootstrap bootstrap;

    private Channel channel;



    public void disconnect(byte[] nodeId) {


    }

    /**
     * 重连
     * @param nodeId
     */
    private void reconnect(byte[] nodeId) {


    }


    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }


    //32字节hex
    public byte[] sendData(String peerId, ProtocolEnum protocol, byte[] request, int time)  {
        try {
            QuicConnection peerConnection = getPeerConnection(peerId);
            if (peerConnection!=null){
                P2PMessage p2PMessage = newRequestMessage(CommonConfig.getSelf().getId(), protocol, request);
                byte[] serialize = p2PMessage.serialize();
                CompletableFuture<QuicMsg> responseFuture = new CompletableFuture<>();
                RESPONSE_FUTURECACHE.put(bytesToHex(p2PMessage.getRequestId()), responseFuture);
                //且协议有返回值
                peerConnection.sendData(serialize);
                if (protocol.isHasResponse()){
                    return responseFuture.get(time, TimeUnit.MILLISECONDS).getData();
                }else {
                    return null;
                }
            }else {
                return null;
            }
        }catch (Exception e){
            log.error("发送数据失败",e);
            throw new SendDataEx(e.getMessage(),500);
        }
    }







}
