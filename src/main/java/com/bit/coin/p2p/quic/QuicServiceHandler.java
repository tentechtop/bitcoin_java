package com.bit.coin.p2p.quic;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class QuicServiceHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagramPacket) throws Exception {
        //如果一个远程地址出现大量无效帧就断开连接
        ByteBuf buf = datagramPacket.content();
        InetSocketAddress remote = datagramPacket.sender();
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        QuicFrame quicFrame = null;
        try {
            quicFrame = QuicFrame.decode(buf, remote);
            QuicConnection orCreateConnection = QuicConnectionManager.createOrGetConnection((DatagramChannel)ctx.channel(),local,remote,quicFrame.getConnectionId());
            orCreateConnection.handleFrame(quicFrame);
        } catch (IllegalArgumentException e) {
            // 数据格式错误，记录但不中断处理
            log.warn("[数据包格式错误] remote={}, error={}", remote, e.getMessage());
        } catch (Exception e) {
            // 其他异常记录完整堆栈
            log.error("[数据包处理失败] remote={}, error={}", remote, e.getMessage(), e);
        }
    }


}
