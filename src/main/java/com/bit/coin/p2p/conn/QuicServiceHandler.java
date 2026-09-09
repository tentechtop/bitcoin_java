package com.bit.coin.p2p.conn;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import java.net.InetSocketAddress;

/** Only a signed handshake may be unencrypted. Every other frame requires session authentication. */
@Slf4j
public class QuicServiceHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf input = packet.content();
        InetSocketAddress remote = packet.sender();
        receiveDatagram(input, remote, null);
    }

    static void receiveDatagram(ByteBuf input, InetSocketAddress remote, RelayPath route) {
        try {
            if (input.readableBytes() < SessionCipher.PREFIX_BYTES + QuicFrame.FIXED_HEADER_LENGTH
                    || input.readableBytes() > PathMtuEstimator.MAX_DATAGRAM_BYTES) return;
            int start = input.readerIndex();
            if (input.getInt(start) != SessionCipher.MAGIC) return;
            byte mode = input.getByte(start + 4);
            long connectionId = input.getLong(start + 5);
            if (mode == 0) {
                QuicFrame frame = QuicFrame.decodeFromByteBuf(input.slice(start + SessionCipher.PREFIX_BYTES,
                        input.readableBytes() - SessionCipher.PREFIX_BYTES), remote);
                if (frame.getConnectionId() != connectionId) return;
                frame.setRelayPath(route);
                if (frame.getFrameType() == QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode())
                    QuicConnectionManager.handleConnectRequestFrame(frame);
                else if (frame.getFrameType() == QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode())
                    QuicConnectionManager.completeHandshakeResponse(frame);
                return;
            }
            QuicConnection connection = QuicConnectionManager.getConnection(connectionId);
            if (mode != 1 || connection == null || connection.isExpired()
                    || !java.util.Objects.equals(connection.getRelayPath(), route)
                    || !connection.isExpectedRemoteAddress(remote) || connection.getSessionCipher() == null) return;
            byte[] datagram = new byte[input.readableBytes()];
            input.getBytes(start, datagram);
            ByteBuf plaintext = Unpooled.wrappedBuffer(connection.getSessionCipher().decrypt(datagram));
            try {
                QuicFrame frame = QuicFrame.decodeFromByteBuf(plaintext, remote);
                if (frame.getConnectionId() != connectionId || frame.getFrameType() == QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode()
                        || frame.getFrameType() == QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode()) return;
                connection.recordFrameReceived(datagram.length);
                frame.setRelayPath(route);
                if (RelayTransport.isRelayFrame(frame.getFrameType())) {
                    if (route == null) RelayTransport.receive(connection, frame);
                } else connection.handleFrame(frame);
            } finally { plaintext.release(); }
        } catch (Exception invalid) {
            log.debug("Rejected datagram from {}: {}", remote, invalid.getClass().getSimpleName());
        }
    }
}
