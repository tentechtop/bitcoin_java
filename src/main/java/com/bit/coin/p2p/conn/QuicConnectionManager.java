package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.RequestConnect;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.MultiAddress;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
import static com.bit.coin.config.SystemConfig.SelfPeer;
import static com.bit.coin.p2p.conn.QuicConstants.*;
import static com.bit.coin.p2p.conn.QuicFrame.ALLOCATOR;
import static com.bit.coin.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.coin.utils.ECCWithAESGCM.generateCurve25519KeyPair;
import static com.bit.coin.utils.ECCWithAESGCM.generateSharedSecret;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;


@Slf4j
@Component
public class QuicConnectionManager {

    @Autowired
    private RoutingTable routingTable;

    //閼哄倻鍋D閸掓媽绻涢幒顧廌
    public static final Map<String, Long> PEER_CONNECTION = new ConcurrentHashMap<>();
    //鏉╃偞甯碔D閸掓媽绻涢幒銉ョ杽娓?鏉╃偞甯存稉顓熸箒閼哄倻鍋D
    private static final Map<Long, QuicConnection> CONNECTION_MAP  = new ConcurrentHashMap<>();


    //閸忋劌鐪琔DP闁岸浜?
    public static DatagramChannel Global_Channel = null;// UDP闁岸浜?


    // 鏉╃偞甯存潻鍥ㄦ埂閺冨爼妫块敍鍧閿涘绱?缁夋帗妫ゅú鏄忕┈閸掓瑦绔婚悶?
    private static final long CONNECTION_EXPIRE_TIME = 2000;

    // ===================== 鐎规碍妞傛禒璇插缁捐法鈻煎Ч?=====================
    // 閸楁洜鍤庣粙瀣暰閺冩湹鎹㈤崝鈩冪潨閿涙氨鏁ゆ禍搴㈢槨缁夋帗澧界悰宀冪箾閹恒儲顥呴弻銉礉鐠佸墽鐤嗘稉鍝勭暓閹躲倗鍤庣粙瀣缉閸忓秹妯嗘繅鐐茬安閻劌鍙ч梻?
    private final ScheduledExecutorService connectionHealthExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "quic-connection-health-thread");
        thread.setDaemon(true);
        return thread;
    });

    // 妤傛﹢顣舵禒璇插濮圭媴绱欓崣鎴︹偓浣诡梾閺屻儻绱氶敍?0ms/濞?
    private final ScheduledExecutorService connectionSendExecutor = new ScheduledThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2), // 閺嶇绺剧痪璺ㄢ柤閺?CPU閺嶇绺鹃弫?2閿涘牐鍤︾亸?閿?
            runnable -> {
                Thread thread = new Thread(runnable, "quic-connection-send-thread");
                thread.setDaemon(true);
                return thread;
            }
    );


    // 閸忋劌鐪張鈧径褑绻涢幒銉︽殶闂勬劕鍩楅敍鍫熺壌閹诡喗婀囬崝鈥虫珤閹嗗厴鐠嬪啯鏆ｉ敍?
    private static final int MAX_GLOBAL_CONNECTIONS = 100;
    // 閸楁椌P濮ｅ繒顫楅張鈧径褑绻涢幒銉嚞濮瑰倹鏆熼敍鍫ユЩ濮濄垻鐓弮鍫曟？妤傛﹢顣剁拠閿嬬湴閿?
    private static final int MAX_REQUEST_PER_IP_PER_SECOND = 10;
    // 閸楁槈eerId濮ｅ繐鍨庨柦鐔告付婢堆囧櫢婢跺秷绻涢幒銉嚞濮瑰倹鏆?
    private static final int MAX_REQUEST_PER_PEER_PER_MINUTE = 5;
    // IP姒涙垵鎮曢崡鏇＄箖閺堢喐妞傞梻杈剧礄娑撳瓨妞傞幏澶愮拨鐡掑懘妾篒P閿涘苯宕熸担宥忕窗閸掑棝鎸撻敍?
    private static final int IP_BLACKLIST_EXPIRE_MINUTES = 10;

    // IP鐠囬攱鐪扮拋鈩冩殶閸ｎ煉绱欓崢鐔告箒閿涘奔绱崠鏍箖閺堢喐妞傞梻缈犺礋1缁夋帪绱?
    private static final LoadingCache<String, AtomicInteger> IP_REQUEST_LIMIT = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS) // 閺€閫涜礋1缁夋帟绻冮張鐕傜礉缁儳鍣幒褍鍩楀В蹇曨潡鐠囬攱鐪伴弫?
            .concurrencyLevel(Runtime.getRuntime().availableProcessors())
            .build(new CacheLoader<>() {
                @Override
                public AtomicInteger load(String ip) {
                    return new AtomicInteger(0);
                }
            });

    // PeerId鐠囬攱鐪扮拋鈩冩殶閸ｎ煉绱欓梽鎰煑閸楁槈eerId闁插秴顦茬拠閿嬬湴閿?
    private static final LoadingCache<String, AtomicInteger> PEER_REQUEST_LIMIT = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .concurrencyLevel(Runtime.getRuntime().availableProcessors())
            .build(new CacheLoader<>() {
                @Override
                public AtomicInteger load(String peerId) {
                    return new AtomicInteger(0);
                }
            });

    // IP姒涙垵鎮曢崡鏇礄娑撳瓨妞傞幏澶愮拨鐡掑懘妾篒P閿?
    private static final LoadingCache<String, Boolean> IP_BLACKLIST = CacheBuilder.newBuilder()
            .expireAfterWrite(IP_BLACKLIST_EXPIRE_MINUTES, TimeUnit.MINUTES)
            .concurrencyLevel(Runtime.getRuntime().availableProcessors())
            .build(new CacheLoader<>() {
                @Override
                public Boolean load(String ip) {
                    return false; // 姒涙顓婚張顏呭姒?
                }
            });


    /**
     * 閸氼垰濮╂潻鐐村复濡偓閺屻儱鐣鹃弮鏈垫崲閸斺槄绱板В蹇曨潡閹笛嗩攽娑撯偓濞嗩摡heckConnection
     */
    @PostConstruct
    public void initConnectionCheckTask() {
        // 娴ｅ酣顣堕敍?缁?濞?韫囧啳鐑?濞撳懐鎮?
        connectionHealthExecutor.scheduleAtFixedRate(
                this::checkConnection,
                1,
                1,
                TimeUnit.SECONDS
        );

        // 妤傛﹢顣堕敍?0ms/濞?閸欐垿鈧焦顥呴弻?
        connectionSendExecutor.scheduleAtFixedRate(
                this::checkConnectionSendData,
                0,
                20,
                TimeUnit.MILLISECONDS
        );
    }


    // 闁库偓濮ｄ焦鏌熷▔鏇熸暭闁?
    @PreDestroy
    public void destroyConnectionCheckExecutor() {
        // 閸忔娊妫撮崑銉ユ倣濡偓閺屻儳鍤庣粙瀣潨
        shutdownExecutor(connectionHealthExecutor, "connection-health");
        // 閸忔娊妫撮崣鎴︹偓浣诡梾閺屻儳鍤庣粙瀣潨
        shutdownExecutor(connectionSendExecutor, "connection-send");
    }

    // 閹惰棄褰囬柅姘辨暏閸忔娊妫撮柅鏄忕帆
    private void shutdownExecutor(ScheduledExecutorService executor, String name) {
        log.info("[sanitized invalid encoded log]");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("[sanitized invalid encoded log]");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("[sanitized invalid encoded log]");
        }
        log.info("[sanitized invalid encoded log]");
    }



    /**
     * 闁俺绻冩潻鐐村复ID閼惧嘲褰囨潻鐐村复
     */
    public static QuicConnection getConnection(long connectionId) {
        return CONNECTION_MAP.get(connectionId);
    }

    /**
     * 閼惧嘲褰囨潻鐐村复閹粯鏆?
     */
    public static int getConnectionCount() {
        return CONNECTION_MAP.size();
    }

    /**
     * 閼惧嘲褰囩粭顑跨娑擃亣绻涢幒?
     */
    public static QuicConnection getFirstConnection() {
        return CONNECTION_MAP.values().stream().findFirst().orElse(null);
    }

    /**
     * 濡偓閺屻儴绻涢幒銉︽Ц閸氾箑鐡ㄩ崷?
     */
    public static boolean hasConnection(long connectionId) {
        return CONNECTION_MAP.get(connectionId) != null;
    }

    //閼惧嘲褰囬幍鈧張澶庣箾閹?
    public static Set<QuicConnection> getAllConnections() {
        return new HashSet<>(CONNECTION_MAP.values());
    }

    //閼惧嘲褰囬幍鈧張澶庡Ν閻?
    public static Set<String> getAllPeers() {
        return PEER_CONNECTION.keySet();
    }

    private static synchronized void registerConnection(String peerId, long connectionId, QuicConnection connection) {
        Long oldConnectionId = PEER_CONNECTION.put(peerId, connectionId);
        if (oldConnectionId != null && oldConnectionId != connectionId) {
            QuicConnection oldConnection = CONNECTION_MAP.remove(oldConnectionId);
            if (oldConnection != null && oldConnection != connection) {
                oldConnection.release();
                log.info("[replace duplicate peer connection] peerId={} oldConnectionId={} newConnectionId={}",
                        peerId, oldConnectionId, connectionId);
            }
        }
        CONNECTION_MAP.put(connectionId, connection);
        pruneStalePeerConnections();
    }

    private static synchronized void pruneStalePeerConnections() {
        for (Map.Entry<Long, QuicConnection> entry : CONNECTION_MAP.entrySet()) {
            Long connectionId = entry.getKey();
            QuicConnection connection = entry.getValue();
            if (connection == null) {
                CONNECTION_MAP.remove(connectionId);
                continue;
            }
            String peerId = connection.getPeerId();
            if (peerId == null || peerId.isEmpty()) {
                continue;
            }
            Long activeConnectionId = PEER_CONNECTION.get(peerId);
            if (connection.isExpired() || (activeConnectionId != null && !activeConnectionId.equals(connectionId))) {
                if (activeConnectionId != null && activeConnectionId.equals(connectionId)) {
                    PEER_CONNECTION.remove(peerId, connectionId);
                }
                CONNECTION_MAP.remove(connectionId, connection);
                connection.release();
                log.info("[prune stale peer connection] peerId={} connectionId={} activeConnectionId={}",
                        peerId, connectionId, activeConnectionId);
            }
        }
    }


    /**
     * 閼惧嘲褰囬幍鈧張澶庣箾閹侯檹D
     */
    public static Set<Long> getAllConnectionIds() {
        return CONNECTION_MAP.keySet();
    }

    public static List<QuicConnectionStats> getConnectionStats() {
        pruneStalePeerConnections();
        long now = System.currentTimeMillis();
        List<QuicConnectionStats> stats = new ArrayList<>();
        for (QuicConnection connection : CONNECTION_MAP.values()) {
            if (connection != null) {
                stats.add(connection.snapshotStats(now));
            }
        }
        stats.sort(Comparator.comparing(QuicConnectionStats::peerId,
                Comparator.nullsLast(String::compareTo)));
        return stats;
    }

    /**
     * 濞撳懐鈹栭幍鈧張澶庡Ν閻愮懓鎷版潻鐐村复
     */
    public static void clearAllConnections() {
        //濞撳懐鈹栭懞鍌滃仯閸掓媽绻涢幒?
        PEER_CONNECTION.clear();
        //鐠嬪啰鏁ら幍鈧張澶庣箾閹恒儳娈憆elease閺傝纭?
        CONNECTION_MAP.values().forEach(QuicConnection::release);
        //濞撳懐鈹栨潻鐐村复
        CONNECTION_MAP.clear();
    }

    /**
     * 閸掔娀娅庢潻鐐村复 閸氬本妞傜憰浣稿灩闂勩倛濡悙鐟板煂鏉╃偞甯撮惃鍕偍瀵洩绱濋獮璺轰粻濮濐敼endManager閻ㄥ嫭澧嶉張澶婂絺闁?
     */
    public static void removeConnection(long connectionId) {
        QuicConnection connection = CONNECTION_MAP.remove(connectionId);
        if (connection != null) {
            // 闁插﹥鏂佹潻鐐村复鐠у嫭绨?
            connection.release();
            // 閸掔娀娅庨懞鍌滃仯閸掓媽绻涢幒銉ф畱缁便垹绱?
            PEER_CONNECTION.remove(connection.getPeerId());
        }
    }


    /**
     * 闁俺绻冩潻鐐村复ID閼惧嘲褰囬懞鍌滃仯
     */
    public static String getPeerId(long connectionId) {
        return CONNECTION_MAP.get(connectionId).getPeerId();
    }


    /**
     * 闁俺绻冮懛顏囆掗柌濠傜€烽崷鏉挎絻閺夈儴绻涢幒銉ㄥΝ閻?/ip4/127.0.0.1/quic/8334/p2p/CrACARmu8BpDDBz9XknpqgZzMZRgratLMdEk7C76avaU
     * @param multiAddressString
     * @return
     */
    public static QuicConnection connectRemoteByAddr(String multiAddressString){
        //娑撳秳璐熺粚?
        if (multiAddressString == null || multiAddressString.isEmpty()) {
            return null;
        }
        MultiAddress multiAddress = new MultiAddress(multiAddressString);
        //娴犲懍绮庨弨顖涘瘮IPV4 閸?QUIC閸楀繗顔?
        if (!multiAddress.getIpType().equals("ip4") || !multiAddress.getProtocol().equals(MultiAddress.Protocol.QUIC)) {
            return null;
        }
        String peerIdHex = multiAddress.getPeerIdHex();//閼哄倻鍋D
        if (PEER_CONNECTION.containsKey(peerIdHex)) {
            QuicConnection existing = getConnection(PEER_CONNECTION.get(peerIdHex));
            if (existing != null && !existing.isExpired()) {
                return existing;
            }
            PEER_CONNECTION.remove(peerIdHex);
        }
        String ipAddress = multiAddress.getIpAddress();
        int port = multiAddress.getPort();
        QuicConnection connection = connectRemote(ipAddress,port);
        if (connection == null) {
            PEER_CONNECTION.remove(peerIdHex);
        }
        return connection;
    }

    //閺嶈宓侀懞鍌滃仯ID閼惧嘲褰囨潻鐐村复 hexID
    public static QuicConnection getConnectionByPeerId(String peerId) {
        Long l = PEER_CONNECTION.get(peerId);
        if (l!=null){
            QuicConnection connection = getConnection(l);
            if (connection != null && !connection.isExpired()) {
                return connection;
            }
            PEER_CONNECTION.remove(peerId);
        }
        return null;
    }

    //Base58Id
    public static QuicConnection connectRemoteByBase58Id(String base58Id) {
        byte[] decode = Base58.decode(base58Id);
        String s = bytesToHex(decode);
        return getConnectionByPeerId(s);
    }


    private static QuicConnection connectRemote(String ipAddress, int port) {
        try {
            InetSocketAddress remoteAddress = new InetSocketAddress(ipAddress, port);
            long conId = ID_Generator.nextId();
            long dataId = ID_Generator.nextId();
            //鏉╃偞甯寸拠閿嬬湴鐢?
            QuicFrame reqQuicFrame = new QuicFrame();
            reqQuicFrame.setConnectionId(conId);//閻㈢喐鍨氭潻鐐村复ID
            reqQuicFrame.setDataId(dataId);
            reqQuicFrame.setTotal(0);
            reqQuicFrame.setFrameType(QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode());
            RequestConnect requestConnect = new RequestConnect();
            requestConnect.setPeerId(SelfPeer.getId());
            requestConnect.setLatestHash(mainTipBlock.getHash());
            requestConnect.setLatestHeight(mainTipBlock.getHeight());
            requestConnect.setChainWork(mainTipBlock.getChainWork());
            byte[][] AKeys = generateCurve25519KeyPair();
            byte[] aPrivateKey = AKeys[0];
            byte[] aPublicKey = AKeys[1];
            requestConnect.setVersion(1);
            requestConnect.setSharedSecretPubKey(aPublicKey);
            byte[] signatureData = requestConnect.getSignatureData();
            //閻㈢喐鍨氱粵鎯ф倳
            byte[] signature = Ed25519Signer.fastSign(SelfPeer.getPrivateKey(),signatureData);//缁涙儳鎮?
            requestConnect.setSignature(signature);
            byte[] serialize = requestConnect.serialize();
            reqQuicFrame.setPayload(serialize);
            reqQuicFrame.setRemoteAddress(remoteAddress);
            reqQuicFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+serialize.length);
            //閸欐垿鈧浇绻涢幒銉р€樼拋銈呮姎
            CompletableFuture<QuicFrame> responseFuture = new CompletableFuture<>();
            QUICFRAME_RESPONSE_FUTURECACHE.put(dataId,responseFuture);
            try {
                sendFrameWithoutResponse(reqQuicFrame);
            //鐎涙サBJECT_RESPONSE_FUTURECACHE
            //娴犲孩婀弶銉х处鐎涙ü鑵戦幏鍨煂閸濆秴绨茬敮?5缁夋帟绉撮弮?
            QuicFrame quicFrame = responseFuture.get(5, TimeUnit.SECONDS);
            if (quicFrame!=null){
                return handleConnectResponseFrame(quicFrame,aPrivateKey,aPublicKey);
            }
            return null;
            } finally {
                QUICFRAME_RESPONSE_FUTURECACHE.invalidate(dataId);
            }
        }catch (Exception e){
            log.error("鏉╃偞甯存潻婊呪柤閼哄倻鍋ｆ径杈Е",e);
            return null;
        }
    }


    //婢跺嫮鎮婃潻鐐村复閸濆秴绨茬敮?閻╁瓨甯撮弽瑙勫祦鐢勬儭鐢妇娈戦崘鍛啇閸掓稑缂撴稉鈧稉顏囩箾閹?
    public static QuicConnection handleConnectResponseFrame(QuicFrame quicFrame,byte[] aPrivateKey,byte[] aPublicKey) {
        // 濮濄儵顎?閿涙艾鐔€绾偓閺嶏繝鐛?- 鐢呰閸ㄥ鍨介弬?
        QuicFrameEnum quicFrameEnum = QuicFrameEnum.fromCode(quicFrame.getFrameType());
        if (quicFrameEnum != QuicFrameEnum.CONNECT_RESPONSE_FRAME) {
            log.warn("閺冪姵鏅ラ惃鍕箾閹恒儱鎼锋惔鏂挎姎缁鐎烽敍姝縸", quicFrame.getFrameType());
            return null;
        }
        long connectionId = quicFrame.getConnectionId();
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            log.warn("[sanitized invalid encoded log]");
            return null;
        }
        String remoteIp = remoteAddress.getAddress().getHostAddress();
        if (hasConnection(connectionId)) {
            log.warn("[sanitized invalid encoded log]");
            return null;
        }

        // 濮濄儵顎?閿涙俺袙閺嬫劘顕Ч鍌欑秼閿涘牆鍘涚憴锝嗙€介敍灞藉晙閸嬫瓍eerId閺嶏繝鐛欓敍?
        byte[] payload = quicFrame.getPayload();
        RequestConnect deserialize = RequestConnect.deserialize(payload);
        //妤犲矁鐦夌粵鎯ф倳
        boolean b = deserialize.verifySignature();
        if (!b){
            log.warn("[sanitized invalid encoded log]");
            return null;
        }

        // 閻楀牊婀?缂冩垹绮堕弽锟犵崣閿涘牆甯張澶愨偓鏄忕帆閿涘奔绻氶悾娆欑礆
        int version = deserialize.getVersion();
        if (version != 1) { // 閸嬪洩顔曡ぐ鎾冲閻楀牊婀版稉?閿涘本鐗撮幑顔肩杽闂勫懍绗熼崝陇鐨熼弫?
            log.warn("[sanitized invalid encoded log]");
            return null;
        }

        // 濮濄儵顎?閿涙瓍eerId閺嶏繝鐛?- 闁插秴顦睵eerId鐠囬攱鐪伴梽鎰ウ
        byte[] peerIdBytes = deserialize.getPeerId();
        if (peerIdBytes == null || peerIdBytes.length == 0) {
            log.warn("[sanitized invalid encoded log]");
            return null;
        }
        String peerIdHexString = bytesToHex(peerIdBytes);
        byte[] sharedSecretPubKey = deserialize.getSharedSecretPubKey();
        byte[] sharedSecret = generateSharedSecret(aPrivateKey, sharedSecretPubKey);

        // 濮濄儵顎?0閿涙艾鍨卞楦跨箾閹恒儱鐤勬笟瀣嫙缂傛挸鐡?
        QuicConnection quicConnection = new QuicConnection();
        quicConnection.setPeerId(peerIdHexString);
        quicConnection.setConnectionId(connectionId);
        quicConnection.setRemoteAddress(remoteAddress);
        quicConnection.setLastSeen(System.currentTimeMillis());//鐠佸墽鐤嗘稉鍝勭秼閸撳秵妞傞梻?娑撱倗顫楅崥搴ょ箖閺?
        quicConnection.setOutbound(true);//娑撹濮╂潻鐐村复
        quicConnection.setSharedSecret(sharedSecret);

        registerConnection(peerIdHexString, connectionId, quicConnection);

        //娴ｈ法鏁ょ痪璺ㄢ柤
        new Thread(() -> {
            Peer peer = new Peer();
            peer.setInetSocketAddress(remoteAddress);
            peer.setId(hexToBytes(peerIdHexString));
            peer.setHeight(deserialize.getLatestHeight());
            peer.setHashHex(bytesToHex(deserialize.getLatestHash()));
            peer.setChainWork(deserialize.getChainWork());

            P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), ProtocolEnum.Handshake_Success_V1, peer.serialize());
            byte[] serialize1 = null;
            try {
                serialize1 = p2PMessage.serialize();
                QuicMsg quicMsg = new QuicMsg();
                quicMsg.setConnectionId(connectionId);
                quicMsg.setDataId(ID_Generator.nextId());
                quicMsg.setData(serialize1);
                pushCompleteMsg(quicMsg);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
        return quicConnection;
    }



    /**
     * 閺€璺哄煂鏉╃偞甯寸拠閿嬬湴鐢冩倵鐠嬪啰鏁ょ拠銉︽煙濞?
     * 闁俺绻冪拠閿嬬湴鏉╃偞甯寸敮褌璐熼懞鍌滃仯閸掓稑缂撴稉鈧稉顏囩箾閹?
     * 鐞氼偄濮╂潻鐐村复
     * 闁俺绻冪拠閿嬬湴鏉╃偞甯寸敮褌璐熼懞鍌滃仯閸掓稑缂撴稉鈧稉顏囩箾閹恒儻绱欑悮顐㈠З鏉╃偞甯撮敍?
     * 閺傛澘顤冮敍姘弿缂佹潙瀹抽梼鍙夊Б閺堝搫鍩楅敍宀勬Щ濮濄垺浼撻幇蹇氼嚞濮瑰倹鏁鹃崙?
     */
    public static QuicConnection handleConnectRequestFrame(QuicFrame quicFrame) {
        // 濮濄儵顎?閿涙艾鐔€绾偓閺嶏繝鐛?- 鐢呰閸ㄥ鍨介弬?
        QuicFrameEnum quicFrameEnum = QuicFrameEnum.fromCode(quicFrame.getFrameType());
        if (quicFrameEnum != QuicFrameEnum.CONNECT_REQUEST_FRAME) {
            log.warn("閺冪姵鏅ラ惃鍕箾閹恒儴顕Ч鍌氭姎缁鐎烽敍姝縸", quicFrame.getFrameType());
            return null;
        }

        // 濮濄儵顎?閿涙俺骞忛崣鏍ㄧ壋韫囧啫寮弫?
        long connectionId = quicFrame.getConnectionId();
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            log.warn("[sanitized invalid encoded log]");
            return null;
        }
        String remoteIp = remoteAddress.getAddress().getHostAddress();

        //閸欐垿鈧浇绻涢幒銉р€樼拋銈呮姎
        QuicFrame responseFrame = new QuicFrame();
        try {
            // 濮濄儵顎?閿涙岸绮﹂崥宥呭礋閺嶏繝鐛?- 閹峰绮P閻╁瓨甯撮幏鎺旂卜
            if (IP_BLACKLIST.get(remoteIp)) {
                log.warn("IP {} 婢跺嫪绨鎴濇倳閸楁洩绱濋幏鎺旂卜鏉╃偞甯寸拠閿嬬湴", remoteIp);
                return null;
            }

            // 濮濄儵顎?閿涙艾鍙忕仦鈧潻鐐村复閺佺増鐗庢?- 鏉堟儳鍩屾稉濠囨閹锋帞绮?
            if (CONNECTION_MAP.size() >= MAX_GLOBAL_CONNECTIONS) {
                log.warn("[sanitized invalid encoded log]");
                return null;
            }

            // 濮濄儵顎?閿涙P鐠囬攱鐪版０鎴犲芳闂勬劖绁?- 濮ｅ繒顫楃拠閿嬬湴閺佹媽绉撮梽鎰灟閹峰绮?
            AtomicInteger ipRequestCount = IP_REQUEST_LIMIT.get(remoteIp);
            int currentIpCount = ipRequestCount.incrementAndGet();
            if (currentIpCount > MAX_REQUEST_PER_IP_PER_SECOND) {
                log.warn("[sanitized invalid encoded log]");
                IP_BLACKLIST.put(remoteIp, true); // 閸旂姴鍙嗘鎴濇倳閸?
                return null;
            }

            // 濮濄儵顎?閿涙俺绻涢幒顧廌閸烆垯绔撮幀褎鐗庢?- 闂冨弶顒涢柌宥咁槻鏉╃偞甯碔D閺€璇插毊
            if (hasConnection(connectionId)) {
                log.warn("[sanitized invalid encoded log]");
                return null;
            }

            // 濮濄儵顎?閿涙俺袙閺嬫劘顕Ч鍌欑秼閿涘牆鍘涚憴锝嗙€介敍灞藉晙閸嬫瓍eerId閺嶏繝鐛欓敍?
            byte[] payload = quicFrame.getPayload();
            RequestConnect deserialize = RequestConnect.deserialize(payload);
            //妤犲矁鐦夌粵鎯ф倳
            boolean b = deserialize.verifySignature();
            if (!b){
                log.warn("[sanitized invalid encoded log]");
                return null;
            }

            // 閻楀牊婀?缂冩垹绮堕弽锟犵崣閿涘牆甯張澶愨偓鏄忕帆閿涘奔绻氶悾娆欑礆
            int version = deserialize.getVersion();
            if (version != 1) { // 閸嬪洩顔曡ぐ鎾冲閻楀牊婀版稉?閿涘本鐗撮幑顔肩杽闂勫懍绗熼崝陇鐨熼弫?
                log.warn("[sanitized invalid encoded log]");
                return null;
            }

            // 濮濄儵顎?閿涙瓍eerId閺嶏繝鐛?- 闁插秴顦睵eerId鐠囬攱鐪伴梽鎰ウ
            byte[] peerIdBytes = deserialize.getPeerId();
            if (peerIdBytes == null || peerIdBytes.length == 0) {
                log.warn("[sanitized invalid encoded log]");
                return null;
            }
            String peerIdHexString = bytesToHex(peerIdBytes);
            AtomicInteger peerRequestCount = PEER_REQUEST_LIMIT.get(peerIdHexString);
            int currentPeerCount = peerRequestCount.incrementAndGet();
            if (currentPeerCount > MAX_REQUEST_PER_PEER_PER_MINUTE) {
                log.warn("[sanitized invalid encoded log]");
                return null;
            }

            // 濮濄儵顎?閿涙岸鐝☉鍫ｂ偓妤佹惙娴ｆ粣绱欑€靛棝鎸滈悽鐔稿灇閿? 閹碘偓閺堝鐗庢宀勨偓姘崇箖閸氬骸鍟€閹笛嗩攽閿涘矂浼╅崗宥嗘爱鐠愮绁┃?
            byte[] aPublicKey = deserialize.getSharedSecretPubKey();
            byte[][] keyPair = generateCurve25519KeyPair();
            byte[] bPrivateKey = keyPair[0];
            byte[] bPublicKey = keyPair[1];
            byte[] sharedSecret = generateSharedSecret(bPrivateKey, aPublicKey);

            // 濮濄儵顎?0閿涙艾鍨卞楦跨箾閹恒儱鐤勬笟瀣嫙缂傛挸鐡?
            QuicConnection quicConnection = new QuicConnection();
            quicConnection.setPeerId(peerIdHexString);
            quicConnection.setConnectionId(connectionId);
            quicConnection.setRemoteAddress(remoteAddress);
            quicConnection.setLastSeen(System.currentTimeMillis());//鐠佸墽鐤嗘稉鍝勭秼閸撳秵妞傞梻?娑撱倗顫楅崥搴ょ箖閺?
            quicConnection.setOutbound(false);//闂堢偘瀵岄崝銊ㄧ箾閹?
            quicConnection.setSharedSecret(sharedSecret);

            // 缂傛挸鐡ㄦ潻鐐村复閿涘牆甯張澶愨偓鏄忕帆閿涘奔绻氶悾娆欑礆
            registerConnection(peerIdHexString, connectionId, quicConnection);

            //娴ｈ法鏁ょ痪璺ㄢ柤
            new Thread(() -> {
                RequestConnect responseConnect = new RequestConnect();
                responseConnect.setPeerId(SelfPeer.getId());
                responseConnect.setVersion(1);
                responseConnect.setLatestHash(mainTipBlock.getHash());
                responseConnect.setLatestHeight(mainTipBlock.getHeight());
                responseConnect.setChainWork(mainTipBlock.getChainWork());
                responseConnect.setSharedSecretPubKey(bPublicKey);

                byte[] signatureData = responseConnect.getSignatureData();
                //閻㈢喐鍨氱粵鎯ф倳
                byte[] signature = Ed25519Signer.fastSign(SelfPeer.getPrivateKey(),signatureData);//缁涙儳鎮?
                responseConnect.setSignature(signature);

                byte[] serialize = responseConnect.serialize();
                responseFrame.setConnectionId(connectionId);
                responseFrame.setDataId(quicFrame.getDataId());
                responseFrame.setTotal(1);
                responseFrame.setFrameType(QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode());
                responseFrame.setPayload(serialize);
                responseFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+serialize.length);
                responseFrame.setRemoteAddress(remoteAddress);
                //閸欐垿鈧浇绻涢幒銉р€樼拋銈呮姎
                sendFrameWithoutResponse(responseFrame);
                log.info("閹存劕濮涙稉绡渆erId {}閿涘湜P閿涙}閿涘鍨卞楦跨箾閹恒儻绱濇潻鐐村复ID閿涙}", peerIdHexString, remoteIp, connectionId);


                Peer peer = new Peer();
                peer.setInetSocketAddress(remoteAddress);
                peer.setId(hexToBytes(peerIdHexString));
                peer.setHeight(deserialize.getLatestHeight());
                peer.setHashHex(bytesToHex(deserialize.getLatestHash()));
                peer.setChainWork(deserialize.getChainWork());

                P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), ProtocolEnum.Handshake_Success_V1, peer.serialize());
                byte[] serialize1 = null;
                try {
                    serialize1 = p2PMessage.serialize();
                    QuicMsg quicMsg = new QuicMsg();
                    quicMsg.setConnectionId(connectionId);
                    quicMsg.setDataId(ID_Generator.nextId());
                    quicMsg.setData(serialize1);
                    pushCompleteMsg(quicMsg);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).start();

            return quicConnection;
        } catch (Exception e) {
            log.error("[sanitized invalid encoded log]");
            return null;
        }
    }





    /**
     * 濮ｅ繒顫楅幍褑顢戦惃鍕箾閹恒儲顥呴弻銉┾偓鏄忕帆閿?
     * 1. 閸忓牊顥呴弻銉ㄧ箾閹恒儲妲搁崥锕佺箖閺堢噦绱?缁夋帗妫ゅú鏄忕┈閿涘绱濇潻鍥ㄦ埂閸掓瑧娲块幒銉︾閻炲棴绱欓柌濠冩杹鐠у嫭绨?閸掔娀娅庣槐銏犵穿閿?
     * 2. 閺堫亣绻冮張鐔烘畱鏉╃偞甯撮敍宀冪殶閻⑩暚ing閺傝纭堕崣鎴︹偓浣哥妇鐠?
     * 閺嶇绺炬导妯哄閿涙俺绻冮張鐔荤箾閹恒儰绗夐崣鎴︹偓涔竔ng閿涘本绔婚悶鍡涒偓鏄忕帆閺囩繝寮楃拫顭掔礉婢跺嫮鎮婄粚鐑樺瘹闁藉牐绔熼悾?
     */
    public void checkConnection() {
        long currentTime = System.currentTimeMillis();
        // 娴ｈ法鏁ゆ潻顓濆敩閸ｃ劑浜堕崢鍡礉闁灝鍘oncurrentModificationException
        Iterator<Map.Entry<Long, QuicConnection>> iterator = CONNECTION_MAP.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Long, QuicConnection> entry = iterator.next();
            long connectionId = entry.getKey();
            QuicConnection connection = entry.getValue();
            // 缁岄缚绻涢幒銉ф纯閹恒儲绔婚悶鍡礄闂冩彃灏介幀褏绱粙瀣剁礆
            if (connection == null) {
                log.warn("[sanitized invalid encoded log]");
                iterator.remove(); // 閸掔娀娅嶤ONNECTION_MAP娑擃厾娈戠粚楦跨箾閹?
                continue;
            }
            //鏉╃偞甯撮弰顖氭儊鐞氼偅鐖ｇ拋棰佽礋鏉╁洦婀?
            if (connection.isExpired()) {
                //閸欏苯鍨归梽?
                PEER_CONNECTION.remove(connection.getPeerId());
                CONNECTION_MAP.remove(connectionId);
                continue;
            }

            try {
                String peerId = connection.getPeerId();
                long lastSeen = connection.getLastSeen();

                // 濮濄儵顎?閿涙矮绱崗鍫燁梾閺屻儴绻涢幒銉︽Ц閸氾箒绻冮張鐕傜礄2缁夋帗妫ゅú鏄忕┈閿?
                if (currentTime - lastSeen > CONNECTION_EXPIRE_TIME) {
                    log.info("[sanitized invalid encoded log]");
                    // 濮濄儵顎?.2閿涙岸鍣撮弨鎹愮箾閹恒儴绁┃鎰剁礄閺嶇绺鹃敍灞界箑妞ょ粯澧界悰宀嬬礆
                    connection.release();

                    log.debug("[sanitized invalid encoded log]");

                    // 濮濄儵顎?.3閿涙艾鍨归梽銈堝Ν閻愮D閸掓媽绻涢幒顧廌閻ㄥ嫮鍌ㄥ鏇礄婢跺嫮鎮妏eerId娑撹櫣鈹栭惃鍕珶閻ｅ矉绱?
                    if (peerId != null && !peerId.isEmpty()) {
                        PEER_CONNECTION.remove(peerId);
                        log.debug("[sanitized invalid encoded log]");
                    } else {
                        log.warn("[sanitized invalid encoded log]");
                    }

                    // 濮濄儵顎?.4閿涙艾鍨归梽顥㎡NNECTION_MAP娑擃厾娈戞潻鐐村复閿涘牐鍑禒锝呮珤鐎瑰鍙忛崚鐘绘珟閿?
                    iterator.remove();
                    log.info("[sanitized invalid encoded log]");
                    continue; // 鏉╁洦婀℃潻鐐村复閺冪娀娓堕崣鎴︹偓涔竔ng閿涘瞼娲块幒銉ヮ槱閻炲棔绗呮稉鈧稉?
                }

                // 濮濄儵顎?閿涙碍婀潻鍥ㄦ埂閻ㄥ嫯绻涢幒銉礉閸欐垿鈧垢ing韫囧啳鐑?
                //婵″倹鐏夐弰顖氬毉缁旀瑨绻涢幒銉﹀ping
                connection.ping();
                log.debug("[connection ping] connectionId={} peerId={} outbound={}",
                        connectionId, peerId, connection.isOutbound());
                //濞撳懐鎮婃潻鍥ㄦ埂閺佺増宓?
                connection.cleanExpiredData();


            } catch (Exception e) {
                // 閸楁洑閲滄潻鐐村复瀵倸鐖舵稉宥呭閸濆秵鏆ｆ担鎾搭梾閺屻儲绁︾粙瀣剁礉瀵搫鍩楀〒鍛倞瀵倸鐖舵潻鐐村复
                log.error("[sanitized invalid encoded log]");
                try {
                    // 瀵搫鍩楅柌濠冩杹鏉╃偞甯寸挧鍕爱
                    connection.release();

                    // 瀵搫鍩楅崚鐘绘珟閼哄倻鍋ｇ槐銏犵穿閿涘牆顦╅悶鍞抏erId娑撹櫣鈹栭敍?
                    String peerId = connection.getPeerId();
                    if (peerId != null && !peerId.isEmpty()) {
                        PEER_CONNECTION.remove(peerId);
                    }

                    // 瀵搫鍩楅崚鐘绘珟CONNECTION_MAP娑擃厾娈戞潻鐐村复
                    iterator.remove();
                    log.warn("[sanitized invalid encoded log]");
                } catch (Exception ex) {
                    log.error("[sanitized invalid encoded log]");
                }
            }
        }
    }

    private void checkConnectionSendData() {
        if (CONNECTION_MAP.isEmpty()) {
            return;
        }
        for (QuicConnection conn : CONNECTION_MAP.values()) {
            // 鐠哄疇绻冩潻鍥ㄦ埂鏉╃偞甯撮敍宀勪缉閸忓秵妫ら弫鍫ｎ吀缁?
            if (conn.isExpired()) {
                continue;
            }
            conn.pickUnAckedFrames();
        }
    }





    //閹貉冨煑閸欐垿鈧線鈧喎瀹?濞岋紕顫楅張鈧径?M閺佺増宓?
    public static void sendFrameWithoutResponse(QuicFrame quicFrame) {
        if (quicFrame == null) {
            log.error("[sanitized invalid encoded log]");
            return;
        }
        long dataId = quicFrame.getDataId();
        long connectionId = quicFrame.getConnectionId();
        ByteBuf buf = null;
        try {
            // 2. 閸掑棝鍘ら崘鍛摠楠炲墎绱惍浣告姎閺佺増宓?
            buf = ALLOCATOR.buffer();
            quicFrame.encode(buf);
            // 3. 閺嬪嫬缂揢DP閺佺増宓侀崠?
            DatagramPacket packet = new DatagramPacket(buf, quicFrame.getRemoteAddress());
            // 4. 瀵倹顒為崣鎴︹偓?
            Global_Channel.writeAndFlush(packet);
            QuicConnection connection = getConnection(connectionId);
            if (connection != null) {
                connection.recordFrameSent(quicFrame.getFrameTotalLength());
            }
            // 濞夈劍鍓伴敍姘劃婢跺嫯绻戦崶鐎焤ue娴犲懓銆冪粈鎭掆偓灞藉絺闁浇顕Ч鍌氬嚒閹绘劒姘﹂崚鐧梕tty閸欐垿鈧線妲﹂崚妞尖偓宥忕礉娑撳秳鍞悰銊ュ絺闁焦鍨氶崝?
            log.debug("[閺堫剙婀撮崣鎴︹偓浣筋嚞濮瑰倸鍑￠幓鎰唉] 閺佺増宓両D:{} 鏉╃偞甯碔D:{}", dataId, connectionId);
        } catch (Exception e) {
            // 5. 缂傛牜鐖滃鍌氱埗婢跺嫮鎮婇敍鍫ュ櫞閺€鎯у敶鐎涙﹫绱濋柆鍨帳濞夊嫭绱￠敍?
            log.error("[閺堫剙婀撮崣鎴︹偓浣哥磽鐢珖 閺佺増宓両D:{} 鏉╃偞甯碔D:{}閿涘苯甯崶鐙呯窗{}",
                    dataId, connectionId, e.getMessage());
            //閹垫挸宓冨鍌氱埗閸樼喎娲?
            log.error("瀵倸鐖堕崢鐔锋礈閿涙}",e.getMessage());
            if (buf != null) {
                buf.release(); // 閹靛濮╅柌濠冩杹ByteBuf閿涘矂妲诲銏犲敶鐎涙ɑ纭犲?
            }
        }
    }



    public static boolean disConnectRemoteByPeerId(String peerId){
        //閺傤厼绱戦懞鍌滃仯娑撳澧嶉張澶屾畱鏉╃偞甯?
        if (PEER_CONNECTION.containsKey(peerId)){
            Long conId = PEER_CONNECTION.remove(peerId);
            if (conId!=null) {
                QuicConnection connection = getConnection(conId);
                if (connection != null) {
                    //閸欐垿鈧椒绗呯痪鍨姎
                    QuicFrame quicFrame = new QuicFrame();
                    quicFrame.setConnectionId(conId);
                    quicFrame.setDataId(0);
                    quicFrame.setFrameType(QuicFrameEnum.OFF_FRAME.getCode());
                    quicFrame.setTotal(1);
                    quicFrame.setSequence(0);
                    quicFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
                    quicFrame.setRemoteAddress(connection.getRemoteAddress());
                    sendFrameWithoutResponse(quicFrame);
                    connection.release();
                    CONNECTION_MAP.remove(conId);
                }
            }
        }
        return true;
    }





    //32鐎涙濡環ex
    public static byte[] staticSendData(String peerId, ProtocolEnum protocol, byte[] request, int time)  {
        try {
            QuicConnection peerConnection = getConnectionByPeerId(peerId);
            if (peerConnection!=null && !peerConnection.isExpired()){
                P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), protocol, request);
                byte[] serialize = p2PMessage.serialize();
                CompletableFuture<QuicMsg> responseFuture = null;
                String requestId = bytesToHex(p2PMessage.getRequestId());
                if (protocol.isHasResponse()) {
                    responseFuture = new CompletableFuture<>();
                    MSG_RESPONSE_FUTURECACHE.put(requestId, responseFuture);
                }
                //娑撴柨宕楃拋顔芥箒鏉╂柨娲栭崐?
                peerConnection.sendData(serialize);
                if (protocol.isHasResponse()){
                    try {
                        return responseFuture.get(time, TimeUnit.MILLISECONDS).getData();
                    } finally {
                        MSG_RESPONSE_FUTURECACHE.invalidate(requestId);
                    }
                }else {
                    return null;
                }
            }
            PEER_CONNECTION.remove(peerId);
            return null;
        }catch (Exception e){
            log.error("[sanitized invalid encoded log]");
            return null;
        }
    }

    /**
     * 閼惧嘲褰囬崷銊у殠閻ㄥ嫯濡悙鐟板灙鐞?
     * 閸︺劎鍤庨懞鍌滃仯鐎规矮绠熼敍姘冲殾鐏忔垶婀佹稉鈧稉顏呮箒閺佸牏娈戞潻鐐村复閿涘牊婀潻鍥ㄦ埂閵嗕線娼径杈ㄦ櫏閵嗕焦婀佽箛鍐儲閿?
     *
     * @return 閸︺劎鍤庨懞鍌滃仯ID闂嗗棗鎮?
     */
    public static Set<String> getOnlinePeerIds() {
        Set<String> onlinePeers = new HashSet<>();

        // 闁秴宸婚幍鈧張澶庡Ν閻愬湱娈戞潻鐐村复閺勭姴鐨?
        for (Map.Entry<String, Long> entry : PEER_CONNECTION.entrySet()) {
            String peerId = entry.getKey();
            Long connectionIds = entry.getValue();
            if (connectionIds!=null){
                QuicConnection connection = getConnection(connectionIds);
                if (connection!=null && !connection.isExpired()){
                    onlinePeers.add(peerId);
                }else {
                    //缁夊娅?
                    PEER_CONNECTION.remove(peerId);
                }
            }else {
                //缁夊娅?
                PEER_CONNECTION.remove(peerId);
            }
        }

        log.info("[閼惧嘲褰囬崷銊у殠閼哄倻鍋 閸忚鲸澹橀崚鐨梷娑擃亜婀痪鑳Ν閻愮櫢绱濋懞鍌滃仯閸掓銆冮敍姝縸", onlinePeers.size(), onlinePeers);
        return onlinePeers;
    }

}
