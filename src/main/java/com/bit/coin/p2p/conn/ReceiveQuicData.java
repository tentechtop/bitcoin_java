package com.bit.coin.p2p.conn;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.bit.coin.database.rocksDb.RocksDb.intToBytes;
import static com.bit.coin.p2p.conn.QuicConstants.pushCompleteMsg;

@Slf4j
@Data
public class ReceiveQuicData extends QuicData{

    //寮€濮嬫帴鏀舵椂闂?
    private long startReceiveTime;

    //瓒呮椂鏃堕棿 TODO 浼氭寜鐓у钩鍧囨椂闂磋绠?
    private long timeout = 2500;//鐩墠鏄?.5绉掕秴鏃?

    //杩囨湡鏃堕棿 TODO 浼氭寜鐓у钩鍧囨椂闂磋绠?
    private long expireTime = 5000;//鐩墠鏄?绉掕繃鏈?

    //鏄惁瀹屾垚鎺ユ敹
    private volatile boolean isCompleted = false;
    // 宸茬粡鎺ユ敹鍒扮殑甯у簭鍒楀彿
    private final Set<Integer> receivedSequences = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Deque<Integer> latestReceivedSequences = new ConcurrentLinkedDeque<>();


    //鑷€傚簲ACK绛栫暐
    private int lastAckSize = 0;
    private long lastAckTime = System.currentTimeMillis();
    private static final int ACK_BATCH_SIZE = 10;  // 姣?0甯у彂閫佷竴娆℃壒閲廇CK
    private static final long ACK_MAX_DELAY_MS = 100;  // 鏈€澶у欢杩?00ms




    synchronized public ReceiveResult handleFrame(QuicFrame quicFrame,int receiveWindow) {
        int sequence = quicFrame.getSequence();
        int total = quicFrame.getTotal();
        long dataId = quicFrame.getDataId();
        long connectionId = quicFrame.getConnectionId();


        // ========== 閲嶅甯у鐞?==========
        if (receivedSequences.contains(sequence)) {
            log.debug("[閲嶅甯 杩炴帴ID:{} 鏁版嵁ID:{} 搴忓垪鍙?{} 鎬诲抚鏁?{}锛岀洿鎺ュ洖澶岮CK",
                    connectionId, dataId, sequence, total);
            QuicFrame ackFrame = buildAckFrame(quicFrame,receiveWindow);
            return new ReceiveResult(false,isCompleted, ackFrame);
        }
        if (getFrameArray() == null) {
            setFrameArray(new QuicFrame[total]);
            setTotal(total);
            setDataId(dataId);
            setConnectionId(connectionId);
            log.debug("[鍒濆鍖栨暟鎹甝 杩炴帴ID:{} 鏁版嵁ID:{} 鎬诲抚鏁?{}", connectionId, dataId, total);
        }
        getFrameArray()[sequence] = quicFrame;
        receivedSequences.add(sequence);
        updateLatestReceivedSequences(sequence);
        log.debug("[鎺ユ敹甯 杩炴帴ID:{} 鏁版嵁ID:{} 搴忓垪鍙?{} 宸叉帴鏀?{}/{}",
                connectionId, dataId, sequence, receivedSequences.size(), total);
        if (receivedSequences.size() == total) {
            log.info("鎵€鏈夊抚鎺ユ敹瀹屾垚");
            // 鎵€鏈夊抚鎺ユ敹瀹屾垚
            handleReceiveSuccess();
            //鏋勫缓涓€涓狝LLReceive甯?
            //鏈湴鎺ユ敹绐楀彛
            log.debug("鏈湴鎺ユ敹绐楀彛{}",receiveWindow);
            QuicFrame ackFrame = buildAllAckFrame(quicFrame,receiveWindow);
            //绔嬪嵆杩斿洖涓€涓叏閮ㄦ帴鏀跺畬鎴愬抚
            return new ReceiveResult(true,true, ackFrame);
        }else{
            // 璁＄畻褰撳墠宸叉帴鏀跺抚鏁般€佽窛绂讳笂娆CK鐨勮€楁椂
            int currentReceivedSize = receivedSequences.size();
            long elapsedSinceLastAck = System.currentTimeMillis() - lastAckTime;
            // 璁＄畻浠庝笂娆CK鍚庢柊鏀跺埌鐨勫抚鏁?
            int newReceivedFrames = currentReceivedSize - lastAckSize;

            // ========== 鑷€傚簲ACK瑙﹀彂閫昏緫锛堜紭鍏堢骇锛氳秴鏃?> 鏂版敹10甯э級 ==========
            QuicFrame ackFrame = null;

            // 绗竴姝ワ細鍏堝垽鏂槸鍚﹁秴鏃讹紙瓒呮椂鐩存帴瑙﹀彂ACK锛?
            if (elapsedSinceLastAck > ACK_MAX_DELAY_MS) {
                ackFrame = buildBitMapAckFrame(quicFrame, receiveWindow);

                log.debug("[瑙﹀彂ACK-瓒呮椂] 杩炴帴ID:{} 鏁版嵁ID:{} 瓒呮椂{}ms锛堥槇鍊納}ms锛夛紝鍙戦€佷綅鍥続CK",
                        quicFrame.getConnectionId(), quicFrame.getDataId(), elapsedSinceLastAck, ACK_MAX_DELAY_MS);
            }
            // 绗簩姝ワ細鏈秴鏃跺垯鍒ゆ柇鏄惁鏂版敹鍒?0甯?
            else if (newReceivedFrames >= ACK_BATCH_SIZE) {
                ackFrame = buildBatchAckFrame(quicFrame, receiveWindow);
                log.debug("[瑙﹀彂ACK-鏂版敹甯ф暟] 杩炴帴ID:{} 鏁版嵁ID:{} 鏂版敹{}甯э紙闃堝€納}甯э級锛屽彂閫佹壒閲廇CK",
                        quicFrame.getConnectionId(), quicFrame.getDataId(), newReceivedFrames, ACK_BATCH_SIZE);
            }
            if (ackFrame != null) {
                // 鍙戦€丄CK锛屽苟鏇存柊ACK鐘舵€?                lastAckSize = currentReceivedSize;
                lastAckTime = System.currentTimeMillis();
                return new ReceiveResult(true, false, ackFrame);
            } else {
                // 涓嶈Е鍙慉CK锛岃繑鍥炵┖
                return new ReceiveResult(true, false, null);
            }
        }
    }

    /**
     * 鏇存柊鏈€鏂版帴鏀剁殑搴忓垪鍙烽槦鍒楋紙鍘婚噸 + 闄愬埗鏈€澶?0涓級
     * @param sequence 鍒氭帴鏀剁殑搴忓垪鍙?
     */
    private void updateLatestReceivedSequences(int sequence) {
        // 1. 鍘婚噸锛氬鏋滈槦鍒椾腑宸叉湁璇ュ簭鍒楀彿锛屽厛绉婚櫎锛堜繚璇佹渶鍚庡嚭鐜板湪闃熷熬锛屽嵆鏈€鏂帮級
        latestReceivedSequences.remove(sequence);
        // 2. 娣诲姞鍒伴槦灏撅紙鏈€鏂版帴鏀剁殑鍦ㄦ渶鍚庯級
        latestReceivedSequences.offerLast(sequence);

        // 3. 闄愬埗闀垮害锛氳秴杩?0涓垯绉婚櫎闃熼锛堟渶鏃х殑锛?
        while (latestReceivedSequences.size() > QuicFrame.BATCH_ACK_MAX_FRAMES) {
            latestReceivedSequences.pollFirst();
        }
    }


    private QuicFrame buildAckFrame(QuicFrame quicFrame,int receiveWindow) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        QuicFrame ackFrame =  new QuicFrame();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(sequence); // ACK甯у簭鍒楀彿鍥哄畾涓?
        ackFrame.setTotal(1); // ACK甯т笉鍒嗙墖
        ackFrame.setFrameType(QuicFrameEnum.DATA_ACK_FRAME.getCode()); // 鑷畾涔夛細ACK甯х被鍨?
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());
        // 璁＄畻鎬婚暱搴︼細鍥哄畾澶撮儴 + 杞借嵎闀垮害
        //null+鎺ユ敹绐楀彛澶у皬
        byte[] bytes = intToBytes(receiveWindow);
        ackFrame.setPayload(bytes);
        ackFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+ackFrame.getPayload().length);
        return ackFrame;
    }



    private QuicFrame buildAllAckFrame(QuicFrame quicFrame,int receiveWindow) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        QuicFrame ackFrame =  new QuicFrame();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(0); // ACK甯у簭鍒楀彿鍥哄畾涓?
        ackFrame.setTotal(1); // ACK甯т笉鍒嗙墖
        ackFrame.setFrameType(QuicFrameEnum.ALL_ACK_FRAME.getCode()); // 鑷畾涔夛細ACK甯х被鍨?
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());
        //null+鎺ユ敹绐楀彛澶у皬
        byte[] bytes = intToBytes(receiveWindow);
        ackFrame.setPayload(bytes);
        ackFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+ackFrame.getPayload().length);
        return ackFrame;
    }



    private void handleReceiveSuccess() {
        isCompleted = true;
    }


    /**
     * 褰撹秴鏃舵椂鍙戦€佷竴涓綅鍥続CK 甯姪鍙戦€佹柟蹇€熸壘鍒扮‘瀹炵殑ACK
     * 鏋勫缓鎵归噺ACK甯э紙8192浣嶆瘮鐗规Ы浣嶏級
     * 姣旂壒妲戒綅涓瘡涓€浣嶄唬琛ㄥ搴斿簭鍒楀彿鐨勫抚鏄惁宸叉帴鏀讹紙1=宸叉帴鏀讹紝0=鏈帴鏀讹級
     * @param quicFrame 鍙傝€冩暟鎹抚锛堢敤浜庤幏鍙栬繛鎺D銆佹暟鎹甀D绛夊厓淇℃伅锛?
     * @return 鎵归噺ACK甯?
     */
     private QuicFrame buildBitMapAckFrame(QuicFrame quicFrame,int receiveWindow) {
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int totalFrames = getTotal(); // 鎬诲抚鏁帮紙浠庡綋鍓嶆帴鏀舵暟鎹殑鍏冧俊鎭腑鑾峰彇锛?


        QuicFrame ackFrame = new QuicFrame();
        ackFrame.setConnectionId(connectionId);
        ackFrame.setDataId(dataId);
        ackFrame.setSequence(0); //鎻愪緵绐楀彛澶у皬
        ackFrame.setTotal(1);
        ackFrame.setFrameType(QuicFrameEnum.BITMAP_ACK_FRAME.getCode()); // 鏂板鎵归噺ACK甯х被鍨?
        ackFrame.setRemoteAddress(quicFrame.getRemoteAddress());

        // 1. 璁＄畻姣旂壒妲戒綅鎵€闇€瀛楄妭鏁帮細鎬诲抚鏁板悜涓婂彇鏁村埌8鐨勫€嶆暟锛?瀛楄妭=8浣嶏級
        // +4 瀛楄妭鐢ㄤ簬鎺ユ敹绐楀彛
        int byteLength = (totalFrames + 7) / 8 + 4; // 渚嬪2048甯ч渶瑕?56瀛楄妭锛?048/8=256锛? 4瀛楄妭绐楀彛

        byte[] ackPayload = new byte[byteLength];

        // 2. 濉厖姣旂壒妲戒綅锛氬凡鎺ユ敹鐨勫簭鍒楀彿瀵瑰簲浣嶇疆缃负1
        for (int sequence : receivedSequences) {
            // 鏍￠獙搴忓垪鍙峰悎娉曟€э紙闃叉瓒婄晫锛?
            if (sequence < 0 || sequence >= totalFrames) {
                log.warn("[鎵归噺ACK寮傚父] 搴忓垪鍙疯秴鍑鸿寖鍥达紝蹇界暐澶勭悊: sequence={}, total={}", sequence, totalFrames);
                continue;
            }
            // 璁＄畻姣旂壒浣嶅湪瀛楄妭鏁扮粍涓殑浣嶇疆
            int byteIndex = sequence / 8;
            int bitIndex = sequence % 8;
            // 瀵瑰簲姣旂壒浣嶇疆1锛堥噰鐢ㄥぇ绔簭锛氱0浣嶈〃绀哄簭鍒楀彿0锛?
            ackPayload[byteIndex] |= (byte) (1 << (7 - bitIndex));
        }

        // 3. 鍦?payload 鏈熬娣诲姞鎺ユ敹绐楀彛(4瀛楄妭,int绫诲瀷)
        int windowIndex = (totalFrames + 7) / 8; // 姣旂壒浣嶅浘涔嬪悗鐨勪綅缃?
        ackPayload[windowIndex] = (byte) ((receiveWindow >> 24) & 0xFF);       // 楂樺瓧鑺?
        ackPayload[windowIndex + 1] = (byte) ((receiveWindow >> 16) & 0xFF);
        ackPayload[windowIndex + 2] = (byte) ((receiveWindow >> 8) & 0xFF);
        ackPayload[windowIndex + 3] = (byte) (receiveWindow & 0xFF);      // 浣庡瓧鑺?

        // 4. 璁剧疆甯ф€婚暱搴︼細鍥哄畾澶撮儴闀垮害 +  payload瀛楄妭鏁?
        int totalLength = QuicFrame.FIXED_HEADER_LENGTH + byteLength;
        ackFrame.setFrameTotalLength(totalLength);
        ackFrame.setPayload(ackPayload);
        return ackFrame;
    }

    //杩斿洖宸茬粡鎺ユ敹鐨勫抚搴忓彿 int[]
    // ====================== 鏂板鏍稿績鏂规硶锛氳繑鍥炲凡鎺ユ敹鐨勫抚搴忓彿int[] ======================
    /**
     * 鑾峰彇宸叉帴鏀剁殑鎵€鏈夊抚搴忓垪鍙凤紙鍗囧簭鎺掑垪锛?
     * <p>
     * 鐗规€э細
     * 1. 绾跨▼瀹夊叏锛氬熀浜庡苟鍙慡et鐨勫揩鐓у鍒讹紝閬垮厤閬嶅巻杩囩▼涓暟鎹彉鏇?
     * 2. 绌哄€煎畨鍏細鏃犲凡鎺ユ敹甯ф椂杩斿洖绌烘暟缁勶紙鑰岄潪null锛夛紝閬垮厤绌烘寚閽?
     * 3. 鏈夊簭鎬э細杩斿洖鏁扮粍鎸夊簭鍒楀彿鍗囧簭鎺掑垪锛屾柟渚胯皟鐢ㄦ柟鎸夐『搴忓鐞?
     * </p>
     * @return 宸叉帴鏀跺抚鐨勫簭鍒楀彿鏁扮粍锛堝崌搴忥級锛屾棤鏁版嵁鏃惰繑鍥炵┖鏁扮粍
     */
    public int[] getReceivedSequencesArray() {
        // 1. 澶嶅埗蹇収锛氶伩鍏嶉亶鍘嗚繃绋嬩腑Set缁撴瀯鍙樺寲锛圕oncurrentHashMap鐨凷et杩唬鍣ㄦ槸寮变竴鑷存€э紝浣嗗鍒舵洿瀹夊叏锛?
        List<Integer> sequenceList = new ArrayList<>(receivedSequences);

        // 2. 绌哄€煎鐞嗭細鏃犳暟鎹椂鐩存帴杩斿洖绌烘暟缁?
        if (sequenceList.isEmpty()) {
            return new int[0];
        }

        // 3. 鍗囧簭鎺掑簭锛氫繚璇佽繑鍥炵殑搴忓垪鍙锋寜椤哄簭鎺掑垪锛堢鍚堝抚浼犺緭鐨勮嚜鐒堕『搴忥級
        Collections.sort(sequenceList);

        // 4. 杞崲涓篿nt鏁扮粍锛堥伩鍏嶅寘瑁呯被鎷嗙鐨勬€ц兘鎹熻€楋級
        int[] result = new int[sequenceList.size()];
        for (int i = 0; i < sequenceList.size(); i++) {
            result[i] = sequenceList.get(i);
        }

        log.debug("[鑾峰彇宸叉帴鏀跺抚搴忓彿] 鏁版嵁ID:{} 宸叉帴鏀跺抚鏁?{} 搴忓垪鍙峰垪琛?{}",
                getDataId(), result.length, Arrays.toString(result));
        return result;
    }

    /**
     * 閲嶈浇鏂规硶锛氳幏鍙栧凡鎺ユ敹鐨勫抚搴忓垪鍙凤紙鏀寔鎸囧畾鎺掑簭瑙勫垯锛?
     * @param reverse 鏄惁鍊掑簭锛坱rue=鍊掑簭锛宖alse=鍗囧簭锛?
     * @return 宸叉帴鏀跺抚鐨勫簭鍒楀彿鏁扮粍锛屾棤鏁版嵁鏃惰繑鍥炵┖鏁扮粍
     */
    public int[] getReceivedSequencesArray(boolean reverse) {
        int[] baseArray = getReceivedSequencesArray();
        if (reverse && baseArray.length > 0) {
            // 鍊掑簭鎺掑垪锛堟瘮濡傞渶瑕佷紭鍏堝鐞嗘渶鏂版帴鏀剁殑甯ф椂浣跨敤锛?
            int[] reversedArray = new int[baseArray.length];
            for (int i = 0; i < baseArray.length; i++) {
                reversedArray[i] = baseArray[baseArray.length - 1 - i];
            }
            return reversedArray;
        }
        return baseArray;
    }



    /**
     * 鏋勫缓鏅€氭壒閲廇CK甯э紙鏈€澶?0甯э紝纭鏈€鏂版帴鏀剁殑甯э級
     * payload缁撴瀯锛?瀛楄妭鏁伴噺 + N脳4瀛楄妭搴忓彿锛圢鈮?0锛?+ 4瀛楄妭鎺ユ敹绐楀彛
     * @param quicFrame 鍙傝€冩暟鎹抚锛堣幏鍙栬繛鎺D銆佹暟鎹甀D绛夊厓淇℃伅锛?
     * @param receiveWindow 鎺ユ敹绐楀彛澶у皬
     * @return 鎵归噺ACK甯?
     */
    private QuicFrame buildBatchAckFrame(QuicFrame quicFrame, int receiveWindow) {
        // 1. 鍩虹鍏冧俊鎭彁鍙?
        long connectionId = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();

        // 2. 鏋勫缓鎵归噺ACK甯у疄渚?
        QuicFrame batchAckFrame = new QuicFrame();
        batchAckFrame.setConnectionId(connectionId);
        batchAckFrame.setDataId(dataId);
        batchAckFrame.setSequence(0); // ACK甯у簭鍒楀彿鍥哄畾涓?
        batchAckFrame.setTotal(1);    // ACK甯т笉鍒嗙墖锛屾€诲抚鏁板浐瀹氫负1
        batchAckFrame.setFrameType(QuicFrameEnum.BATCH_ACK_FRAME.getCode()); // 鎵归噺ACK甯х被鍨嬶紙0x04锛?
        batchAckFrame.setRemoteAddress(remoteAddress);

        // 3. 浠庨槦鍒楀彇銆屾渶鏂扮殑10涓湁鏁堝簭鍒楀彿銆嶏紙鏍稿績浼樺寲锛氭儼鎬ф眰鍊硷紝鍙彇闇€瑕佺殑锛?
        int[] ackSequences = latestReceivedSequences.stream()
                .filter(seq -> seq >= 0 && seq < getTotal()) // 杩囨护鏈夋晥搴忓垪鍙凤紙闃茶秺鐣岋級
                .distinct() // 鍏滃簳鍘婚噸锛堝嵆浣块槦鍒楁湁閲嶅锛岃繖閲屼篃鑳借繃婊わ級
                .sorted(Collections.reverseOrder()) // 鍊掑簭锛氭渶鏂版帴鏀剁殑鍦ㄥ墠
                .limit(QuicFrame.BATCH_ACK_MAX_FRAMES) // 鍙彇鏈€鏂扮殑10涓紝涓嶇闃熷垪澶氶暱
                .mapToInt(Integer::intValue)
                .toArray();

        // 4. 鍏滃簳鏍￠獙锛堟棤鏈夋晥搴忓垪鍙锋椂杩斿洖鍗曞抚ACK锛?
        if (ackSequences.length == 0) {
            log.warn("[鎵归噺ACK寮傚父] 鏃犳湁鏁堝簭鍒楀彿锛岃繛鎺D:{} 鏁版嵁ID:{}", connectionId, dataId);
            return buildAckFrame(quicFrame, receiveWindow);
        }

        // 5. 鍐欏叆payload + 鏇存柊缁熻
        batchAckFrame.writeBatchAckFramePayload(ackSequences, receiveWindow);
        lastAckSize = receivedSequences.size();
        lastAckTime = System.currentTimeMillis();

        log.debug("[鏋勫缓鎵归噺ACK] 杩炴帴ID:{} 鏁版嵁ID:{} 纭鏈€鏂板簭鍒楀彿鏁?{} 鎺ユ敹绐楀彛:{} 搴忓垪鍙峰垪琛?{}",
                connectionId, dataId, ackSequences.length, receiveWindow, Arrays.toString(ackSequences));
        return batchAckFrame;
    }



}
