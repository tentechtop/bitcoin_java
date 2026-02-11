package com.bit.coin.p2p.impl;

import com.bit.coin.config.CommonConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.p2p.PeerService;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolRegistry;
import com.bit.coin.p2p.protocol.impl.*;
import com.bit.coin.p2p.quic.QuicConnection;
import com.bit.coin.p2p.quic.QuicConnectionManager;
import com.bit.coin.p2p.quic.QuicServiceHandler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.bit.coin.config.CommonConfig.self;
import static com.bit.coin.p2p.quic.QuicConnectionManager.*;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;


@Slf4j
@Data
@Component
@Order(1) // 确保服务端优先于客户端初始化
public class PeerServiceImpl implements PeerService {
    @Autowired
    private SystemConfig systemConfig;
    @Autowired
    private CommonConfig commonConfig;
    @Autowired
    private RoutingTable routingTable;

    @Autowired
    private ProtocolRegistry protocolRegistry;

    // QUIC服务器通道
    private Channel quicServerChannel;
    // 事件循环组
    private NioEventLoopGroup eventLoopGroup;

    private SelfSignedCertificate selfSignedCert;
    private ChannelHandler serviceCodec;
    private ChannelHandler clientCodec;

    private Bootstrap bootstrap;

    @Autowired
    private NetworkHandshakeHandler networkHandshakeHandler;
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

    @Autowired
    private PingHandler pingHandler;
    @Autowired
    private TextHandler textHandler;



    @PostConstruct
    public void init() throws IOException, CertificateException, InterruptedException {
        //在项目启动前 用socket 且用8333端口去访问STUN服务器 或者中转服务器 让他们返回 你的公网IP和映射地址 然后再去连接 并主动上报

        startQuicServer();


        //连接引导节点 连接成功发起握手  A发起与X的连接 网络底层连接成功 发起握手 合适就记录 不合适就互相删除 不再打扰

        //连接成功后 握手时能知道对方是内网节点还是外网节点 握手信息会携带自己的本地IP和端口 和netty的IP和端口一对比即可知道

        //A连接X成功后 通过X发现B(且记录B来自与X) A知道B是内网节点 此时A连接B时 同时向X发送一转发消息 X转发给B B收到后也连接A A收到B的回复 此时打洞成功

        //若A2秒内未收到则降级为中继 A标记B 为需要中继才能通信  A每次发送B的消息都通过X B收到后知道这条消息来自于A且通过X转发 B回复A 也通过X转发


        //注册协议
        protocolRegistry.registerResultHandler(ProtocolEnum.Network_handshake_V1,  networkHandshakeHandler);
        protocolRegistry.registerVoidHandler(ProtocolEnum.Handshake_Success_V1,  handshakeSuccessHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.P2P_Find_Node_Req, p2PFindNodeReqHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.PING_V1,  pingHandler);
        protocolRegistry.registerResultHandler(ProtocolEnum.TEXT_V1,  textHandler);
        protocolRegistry.registerVoidHandler(ProtocolEnum.P2P_Broadcast_Simple_Resource,  broadcastResourceHandle);
        protocolRegistry.registerVoidHandler(ProtocolEnum.P2P_Get_Resource_Req,  p2PGetResourceReqHandle);
        protocolRegistry.registerVoidHandler(ProtocolEnum.P2P_Receive_Block,  p2PReceiveBlockHandle);
        protocolRegistry.registerVoidHandler(ProtocolEnum.P2P_Receive_Transaction,  p2PReceiveTxHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.P2P_Query_Block_By_Hash,  p2PQueryBlockByHashHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.P2P_Query_Block_By_Height,  p2PQueryBlockByHeightHandle);
        protocolRegistry.registerResultHandler(ProtocolEnum.P2P_Query_Common_Ancestor,  p2PQueryCommonAncestorHandle);

        //连接引导节点并将引导节点加入到路由表
        connectBootstrapNodes();
    }


    //连接引导节点方法
    public void connectBootstrapNodes() {
        try {
            List<String> bootstrap = systemConfig.getBootstrap();
            for (String bootstrapNode : bootstrap){
                QuicConnection quicConnection = connectRemoteByAddr(bootstrapNode);
            }
            //连接完成后对引导节点进行节点查询
        }catch (Exception e){
            log.error("连接引导节点失败{}",e.getMessage());
        }
    }


    public void startQuicServer()  {
        // 1. 创建事件循环组（UDP无连接，仅需一个线程组）
        //可用核心的一半
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        eventLoopGroup = new NioEventLoopGroup(availableProcessors, new DefaultThreadFactory("udp-io-thread", true));
        try {
            // 2. 配置Bootstrap（UDP使用Bootstrap而非ServerBootstrap）
            bootstrap = new Bootstrap();
            bootstrap.group(eventLoopGroup)
                    .channel(NioDatagramChannel.class) // UDP通道类型
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) // 池化内存分配（减少GC）
                    // UDP接收缓冲区
                    .option(ChannelOption.SO_RCVBUF, 128 * 1024 * 1024)
                    // UDP发送缓冲区
                    .option(ChannelOption.SO_SNDBUF, 128 * 1024 * 1024)
                    .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用（多线程共享端口）
                    .option(ChannelOption.SO_BROADCAST, true) // 支持广播（按需开启）

                    .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(1500))
                    .option(ChannelOption.MAX_MESSAGES_PER_READ, 1024) // 每次IO事件最多读取1024个DatagramPacket
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                            16 * 1024 * 1024, // 低水位：16MB
                            64 * 1024 * 1024  // 高水位：64MB
                    ))
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new QuicServiceHandler());
                        }
                    });

            // 3. 绑定端口并启动
            ChannelFuture sync = bootstrap.bind(commonConfig.getSelf().getPort()).sync();
            log.info("QUIC服务器已启动，监听端口：{}", commonConfig.getSelf().getPort());
            Channel channel = sync.channel();
            Global_Channel = (DatagramChannel)channel;



        }catch (Exception e){
            e.printStackTrace();
        }
    }







    /**
     * 关闭服务器
     */
    @PreDestroy
    public void shutdown() throws InterruptedException {
        // 6. 关闭QUIC服务器Channel
        if (quicServerChannel != null) {
            try {
                quicServerChannel.close().sync();
                log.info("QUIC服务器Channel已关闭");
            } catch (InterruptedException e) {
                log.warn("关闭QUIC Channel时线程中断", e);
                Thread.currentThread().interrupt();
            } finally {
                quicServerChannel = null;
            }
        }

        // 7. 关闭QUIC EventLoopGroup
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS)
                    .addListener(future -> log.info("QUIC EventLoopGroup已关闭"));
            eventLoopGroup = null;
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
