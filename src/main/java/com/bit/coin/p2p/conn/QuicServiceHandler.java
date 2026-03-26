package com.bit.coin.p2p.conn;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import static com.bit.coin.p2p.conn.QuicConnectionManager.getConnection;
import static com.bit.coin.p2p.conn.QuicConnectionManager.handleConnectRequestFrame;
import static com.bit.coin.p2p.conn.QuicConstants.QUICFRAME_RESPONSE_FUTURECACHE;

@Slf4j
public class QuicServiceHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagramPacket) throws Exception {
        //如果一个远程地址出现大量无效帧就断开连接
        ByteBuf buf = datagramPacket.content();
        InetSocketAddress remote = datagramPacket.sender();
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        try {
            QuicFrame quicFrame = QuicFrame.decodeFromByteBuf(buf, remote);
            //如果是连接请求帧
            QuicFrameEnum quicFrameEnum = QuicFrameEnum.fromCode(quicFrame.getFrameType());
            if (quicFrameEnum == QuicFrameEnum.CONNECT_REQUEST_FRAME){
                handleConnectRequestFrame(quicFrame);
            }else if (quicFrameEnum == QuicFrameEnum.CONNECT_RESPONSE_FRAME){
                //如果是连接响应帧
                CompletableFuture<QuicFrame> ifPresent = QUICFRAME_RESPONSE_FUTURECACHE.asMap().remove(quicFrame.getDataId());
                if (ifPresent != null) {
                    //必须有请求才能处理响应 防止攻击
                    ifPresent.complete(quicFrame);
                }
            }else {
                //找到连接
                QuicConnection quicConnection = getConnection(quicFrame.getConnectionId());
                if (quicConnection != null) {
                    //必须有连接才能处理帧 防止攻击
                    quicConnection.handleFrame(quicFrame);
                }
            }
        } catch (IllegalArgumentException e) {
            // 数据格式错误，记录但不中断处理
            log.warn("[数据包格式错误] remote={}, error={}", remote, e.getMessage());
        } catch (Exception e) {
            // 其他异常记录完整堆栈
            log.error("[数据包处理失败] remote={}, error={}", remote, e.getMessage(), e);
        }
    }
}
