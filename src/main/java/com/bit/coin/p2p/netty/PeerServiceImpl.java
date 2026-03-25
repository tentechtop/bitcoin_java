package com.bit.coin.p2p.netty;

import com.bit.coin.config.SystemConfig;
import com.bit.coin.p2p.conn.QuicConnection;
import com.bit.coin.p2p.conn.QuicServiceHandler;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolRegistry;
import com.bit.coin.p2p.protocol.impl.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Set;

import static com.bit.coin.config.SystemConfig.SelfPeer;
import static com.bit.coin.p2p.conn.QuicConnectionManager.*;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Data
@Component
@Order(1) // 确保服务端优先于客户端初始化
public class PeerServiceImpl {

    @Autowired
    private SystemConfig systemConfig;

    // 事件循环组
    private NioEventLoopGroup eventLoopGroup;

    @Autowired
    private ProtocolRegistry protocolRegistry;

    private Bootstrap bootstrap;

    @Autowired
    private RoutingTable routingTable;

    @Autowired
    private TextHandler textHandler;
    @Autowired
    private Text2Handler2 textHandler2;



    @Autowired
    private HandshakeSuccessHandle handshakeSuccessHandle;
    @Autowired
    private P2PFindNodeReqHandle p2PFindNodeReqHandle;
    @Autowired
    private BroadcastResourceHandle broadcastResourceHandle;
    @Autowired
    private P2PGetResourceReqHandle p2PGetResourceReqHandle;
    @Autowired
    private P2PReceiveBlockHandle p2PReceiveBlockHandle;
    @Autowired
    private P2PReceiveTxHandle p2PReceiveTxHandle;
    @Autowired
    private P2PQueryBlockByHashHandle p2PQueryBlockByHashHandle;
    @Autowired
    private P2PQueryBlockByHeightHandle p2PQueryBlockByHeightHandle;
    @Autowired
    private P2PQueryCommonAncestorHandle p2PQueryCommonAncestorHandle;



    @PostConstruct
    public void init() throws IOException, CertificateException, InterruptedException {
        startQuicServer();
        protocolRegistry.registerVoidHandler(ProtocolEnum.TEXT_V1,  textHandler);
        protocolRegistry.registerResultHandler(ProtocolEnum.TEXT_V2,  textHandler2);

        protocolRegistry.registerVoidHandler(ProtocolEnum.Handshake_Success_V1, handshakeSuccessHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.P2P_Find_Node_Req, p2PFindNodeReqHandle);
        protocolRegistry.registerVoidHandler(ProtocolEnum.P2P_Broadcast_Simple_Resource,  broadcastResourceHandle);
        protocolRegistry.registerVoidHandler(ProtocolEnum.P2P_Get_Resource_Req,  p2PGetResourceReqHandle);
        protocolRegistry.registerVoidHandler(ProtocolEnum.P2P_Receive_Block,  p2PReceiveBlockHandle);
        protocolRegistry.registerVoidHandler(ProtocolEnum.P2P_Receive_Transaction,  p2PReceiveTxHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.P2P_Query_Block_By_Hash,  p2PQueryBlockByHashHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.P2P_Query_Block_By_Height,  p2PQueryBlockByHeightHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.P2P_Query_Common_Ancestor,  p2PQueryCommonAncestorHandle);

        connectBootstrapNodes();
    }

    public void startQuicServer() {
        // 1. 初始化UDP IO线程组（核心优化：UDP无连接，单线程足够，避免多线程切换开销）
        int ioThreadCount = 1;
        eventLoopGroup = new NioEventLoopGroup(
                ioThreadCount,
                new DefaultThreadFactory("udp-io-thread", true) // 守护线程，避免阻塞应用关闭
        );

        try {
            // 2. 配置Netty UDP Bootstrap（核心优化：贴合操作系统实际限制，避免无效配置）
            bootstrap = new Bootstrap();
            bootstrap.group(eventLoopGroup)
                    .channel(NioDatagramChannel.class) // UDP通道类型（固定）
                    // 核心：池化内存分配器（减少GC，提升内存复用率）
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    // ========== 缓冲区优化 ==========
                    // UDP发送/接收缓冲区：8MB
                    .option(ChannelOption.SO_SNDBUF, 8 * 1024 * 1024)
                    .option(ChannelOption.SO_RCVBUF, 8 * 1024 * 1024)
                    // ========== UDP基础配置 ==========
                    .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用（多实例部署时有用）
                    .option(ChannelOption.SO_BROADCAST, false) // 本地测试关闭广播（减少不必要开销）

                    // 每次IO事件最多读取16个包（避免IO线程长时间占用，导致后续包排队）
                    .option(ChannelOption.MAX_MESSAGES_PER_READ, 16)
                    // ========== 发送优化 ==========
                    // 写缓冲区水位（合理值，避免高水位过大导致流量控制失效）
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                            4 * 1024 * 1024,  // 低水位：4MB
                            16 * 1024 * 1024  // 高水位：16MB
                    ))
                    // ========== 通道处理器 ==========
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            // 可选：添加Netty日志处理器（调试时开启，生产关闭）
                            // 核心业务处理器（你的QuicServiceHandler）
                            pipeline.addLast(new QuicServiceHandler());
                        }
                    });

            // 3. 绑定端口并启动（同步等待绑定完成）
            ChannelFuture bindFuture = bootstrap.bind(SelfPeer.getPort()).sync();
            if (bindFuture.isSuccess()) {
                // 赋值全局UDP通道（确保后续发送可用）
                Global_Channel = (DatagramChannel) bindFuture.channel();
                log.info("QUIC服务器启动成功 | 监听端口：{} | IO线程数：{} | 缓冲区配置：8MB",
                        SelfPeer.getPort(), ioThreadCount);
            } else {
                log.error("QUIC服务器启动失败 | 端口绑定失败：{}", SelfPeer.getPort());
                throw new RuntimeException("UDP端口绑定失败", bindFuture.cause());
            }

            // 4. 监听通道关闭事件（优雅关闭）
            bindFuture.channel().closeFuture().addListener(future -> {
                log.info("QUIC服务器通道已关闭 | 端口：{}", SelfPeer.getPort());
                // 关闭事件循环组
                if (!eventLoopGroup.isShutdown()) {
                    eventLoopGroup.shutdownGracefully();
                }
            });

        } catch (InterruptedException e) {
            log.error("QUIC服务器启动被中断 | 端口：{}", SelfPeer.getPort(), e);
            Thread.currentThread().interrupt(); // 恢复中断状态
            // 优雅关闭线程组
            if (eventLoopGroup != null) {
                eventLoopGroup.shutdownGracefully();
            }
        } catch (Exception e) {
            log.error("QUIC服务器启动异常 | 端口：{}", SelfPeer.getPort(), e);
            // 优雅关闭线程组
            if (eventLoopGroup != null) {
                eventLoopGroup.shutdownGracefully();
            }
            throw new RuntimeException("QUIC服务器启动失败", e);
        }
    }

    // ========== 新增：优雅关闭方法（配套使用） ==========
    public void shutdownQuicServer() {
        log.info("开始关闭QUIC服务器...");
        // 关闭全局通道
        if (Global_Channel != null && Global_Channel.isActive()) {
            Global_Channel.close().syncUninterruptibly();
        }
        // 优雅关闭事件循环组
        if (eventLoopGroup != null && !eventLoopGroup.isShutdown()) {
            eventLoopGroup.shutdownGracefully(1, 5, java.util.concurrent.TimeUnit.SECONDS)
                    .addListener(future -> {
                        if (future.isSuccess()) {
                            log.info("QUIC服务器IO线程组关闭成功");
                        } else {
                            log.error("QUIC服务器IO线程组关闭失败", future.cause());
                        }
                    });
        }
        log.info("QUIC服务器关闭完成");
    }

    public void connectBootstrapNodes() {
        try {
            List<String> bootstrap = systemConfig.getBootstrap();
            for (String bootstrapNode : bootstrap){
                QuicConnection quicConnection = connectRemoteByAddr(bootstrapNode);
                if (quicConnection!=null){
                    log.info("连接成功");
                }
            }
            //连接完成后对引导节点进行节点查询
        }catch (Exception e){
            log.error("连接引导节点失败{}",e.getMessage());
        }
    }

    public List<Peer> getAllNodes() {
        List<Peer> all = routingTable.getAll();
        Set<String> onlinePeerIds = getOnlinePeerIds();//在onlinePeerIds中的all online字段改成true 不在改成false
        all.forEach(peer -> peer.setOnline(onlinePeerIds.contains(bytesToHex(peer.getId()))));
        return all;
    }

    public List<Peer> getOnlineNodes() {
        return routingTable.getOnlineNodes();
    }
}
