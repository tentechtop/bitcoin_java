package com.bit.coin.net;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.Landmark;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.message.BlockHeadersPayload;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.utils.UInt256;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
import static com.bit.coin.p2p.conn.QuicConnectionManager.staticSendData;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Service
@RequiredArgsConstructor
public class NetServiceImpl implements NetService {

    private static final int MAX_SYNC_PEERS = 8;
    private static final int MAX_HEADERS_PER_REQUEST = BlockHeadersPayload.MAX_HEADERS_PER_RESPONSE;
    private static final int MAX_IN_FLIGHT_BLOCKS = 128;
    private static final int MAX_CONCURRENT_REQUESTS_PER_NODE = 3;
    private static final int BLOCK_FETCH_TIMEOUT_SECONDS = 15;
    private static final int BLOCK_FETCH_RETRY_TIMES = 2;
    private static final int RECENT_ERROR_LIMIT = 20;
    private static final Comparator<SyncPeer> SYNC_PEER_ORDER = Comparator
            .comparing((SyncPeer candidate) -> candidate.tip().chainWork())
            .thenComparingInt(candidate -> candidate.tip().height())
            .thenComparingLong(SyncPeer::lastSeen).reversed();

    private final ExecutorService syncExecutor = new ThreadPoolExecutor(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
            Math.max(8, Runtime.getRuntime().availableProcessors() * 4),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadFactory() {
                private final AtomicInteger threadNum = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "block-sync-thread-" + threadNum.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @Autowired
    private RoutingTable routingTable;

    @Autowired
    private BlockChainServiceImpl blockChainService;

    private final AtomicBoolean syncRunning = new AtomicBoolean(false);
    private final AtomicInteger peerCursor = new AtomicInteger(0);
    private final ConcurrentMap<String, Semaphore> peerSemaphores = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PeerSyncTracker> peerStates = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recentErrors = new ConcurrentLinkedDeque<>();

    private final AtomicReference<String> syncStatus = new AtomicReference<>("IDLE");
    private final AtomicReference<String> syncMessage = new AtomicReference<>("Idle");
    private final AtomicReference<String> bestPeerId = new AtomicReference<>("");
    private final AtomicInteger bestPeerHeight = new AtomicInteger(0);
    private final AtomicInteger localHeightAtStart = new AtomicInteger(0);
    private final AtomicInteger localHeight = new AtomicInteger(0);
    private final AtomicInteger networkHeight = new AtomicInteger(0);
    private final AtomicInteger commonAncestorHeight = new AtomicInteger(0);
    private final AtomicInteger startHeight = new AtomicInteger(0);
    private final AtomicInteger targetHeight = new AtomicInteger(0);
    private final AtomicInteger totalBlocks = new AtomicInteger(0);
    private final AtomicInteger totalHeaders = new AtomicInteger(0);
    private final AtomicInteger downloadedBlocks = new AtomicInteger(0);
    private final AtomicInteger downloadedHeaders = new AtomicInteger(0);
    private final AtomicInteger verifiedBlocks = new AtomicInteger(0);
    private final AtomicInteger verifiedHeaders = new AtomicInteger(0);
    private final AtomicInteger failedBlocks = new AtomicInteger(0);
    private final AtomicInteger inFlightBlocks = new AtomicInteger(0);
    private final AtomicInteger inFlightHeaders = new AtomicInteger(0);
    private final AtomicInteger connectedPeerCount = new AtomicInteger(0);
    private final AtomicInteger syncPeerCount = new AtomicInteger(0);
    private final AtomicLong startedAtMillis = new AtomicLong(0L);
    private final AtomicLong updatedAtMillis = new AtomicLong(0L);
    private final AtomicLong finishedAtMillis = new AtomicLong(0L);

    private volatile Future<?> currentSyncTask;

    private final AtomicReference<String> syncMode = new AtomicReference<>("FULL_BLOCKS");

    @Override
    public SyncProgress startSyncBlock() {
        return startSync(false);
    }

    @Override
    public SyncProgress startSyncBlockHeadersFirst() {
        return startSync(true);
    }

    private SyncProgress startSync(boolean headersFirst) {
        if (!syncRunning.compareAndSet(false, true)) {
            syncMessage.set("Sync is already running");
            touchProgress();
            return getSyncProgress();
        }

        resetProgress();
        syncMode.set(headersFirst ? "HEADERS_FIRST" : "FULL_BLOCKS");
        currentSyncTask = syncExecutor.submit(() -> runSyncSafely(headersFirst));
        return getSyncProgress();
    }

    private void runSyncSafely(boolean headersFirst) {
        try {
            runSync(headersFirst);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed("Sync interrupted", e);
        } catch (Exception e) {
            markFailed("Sync failed: " + e.getMessage(), e);
        } finally {
            inFlightBlocks.set(0);
            inFlightHeaders.set(0);
            syncRunning.set(false);
            finishedAtMillis.compareAndSet(0L, System.currentTimeMillis());
            touchProgress();
        }
    }

    private void runSync(boolean headersFirst) throws Exception {
        setStage("STARTING", "Scanning local chain and connected peers");

        int currentLocalHeight = getCurrentLocalHeight();
        List<Peer> connectedPeers = getConnectedPeers();
        refreshPeerStates(connectedPeers);

        localHeightAtStart.set(currentLocalHeight);
        localHeight.set(currentLocalHeight);
        networkHeight.set(connectedPeers.stream().mapToInt(Peer::getHeight).max().orElse(currentLocalHeight));
        connectedPeerCount.set(connectedPeers.size());

        Map<Peer.ChainTip, List<Peer>> targets = selectSyncTargets(connectedPeers, getCurrentLocalChainWork());
        if (targets.isEmpty()) {
            targetHeight.set(currentLocalHeight);
            setStage("COMPLETED", "No connected peer advertises more cumulative work");
            return;
        }

        Exception lastFailure = null;
        for (var entry : targets.entrySet()) {
            Peer.ChainTip target = entry.getKey();
            UInt256 localWork = getCurrentLocalChainWork();
            if (target.chainWork().compareTo(localWork) <= 0) break;
            try {
                syncTarget(entry.getValue(), target, localWork, headersFirst);
                lastFailure = null;
                break;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                addRecentError("Sync target " + target.hashHex() + " failed: " + e.getMessage());
            }
        }
        localHeight.set(getCurrentLocalHeight());
        if (lastFailure != null) throw lastFailure;
        setStage("COMPLETED", headersFirst ? "Headers-first block sync completed" : "Block sync completed");
    }

    private void syncTarget(List<Peer> syncPeers, Peer.ChainTip target, UInt256 localWork, boolean headersFirst)
            throws Exception {
        resetDownloadProgress();
        targetHeight.set(target.height());
        syncPeerCount.set(syncPeers.size());
        setStage("LOCATING_COMMON_ANCESTOR", "Locating common ancestor");
        Landmark commonAncestor = queryCommonAncestor(syncPeers, target.height() - 1);
        if (commonAncestor == null) {
            throw new IllegalStateException("Unable to locate common ancestor for target " + target.hashHex());
        }

        commonAncestorHeight.set(commonAncestor.getHeight());
        int firstHeight = commonAncestor.getHeight() + 1;
        int blocksToSync = target.height() - commonAncestor.getHeight();
        startHeight.set(firstHeight);
        totalBlocks.set(blocksToSync);
        log.info("Start block sync: mode={}, localHeight={}, targetHeight={}, targetWork={}, commonAncestor={}, peers={}",
                syncMode.get(), getCurrentLocalHeight(), target.height(), target.chainWork(), commonAncestor.getHeight(),
                syncPeers.size());

        if (headersFirst) {
            totalHeaders.set(blocksToSync);
            setStage("DOWNLOADING_HEADERS", "Downloading and verifying block headers");
            List<HeaderSyncRecord> headerChain = syncHeaders(syncPeers, commonAncestor, target);
            HeaderSyncRecord last = headerChain.getLast();
            if (last.chainWork().compareTo(localWork) <= 0) {
                throw new IllegalStateException("Verified headers do not exceed local cumulative work");
            }
            if (last.chainWork().compareTo(getCurrentLocalChainWork()) <= 0) return;
            setStage("DOWNLOADING", "Downloading blocks for verified headers");
            syncBlocksByHeaders(syncPeers, headerChain);
        } else {
            setStage("DOWNLOADING", "Downloading blocks");
            byte[] tipHash = syncBlocks(syncPeers, commonAncestor, target);
            if (blockChainService.getChainWorkByHash(tipHash).compareTo(localWork) <= 0) {
                throw new IllegalStateException("Verified blocks do not exceed local cumulative work");
            }
        }
    }

    private List<HeaderSyncRecord> syncHeaders(
            List<Peer> syncPeers,
            Landmark commonAncestor,
            Peer.ChainTip target
    ) {
        List<HeaderSyncRecord> headerChain = new ArrayList<>();
        byte[] expectedPreviousHash = commonAncestor.getHash();
        UInt256 runningChainWork = blockChainService.getChainWorkByHash(expectedPreviousHash);
        int acceptedHeight = commonAncestor.getHeight();

        while (acceptedHeight < target.height()) {
            int nextHeight = acceptedHeight + 1;
            List<HeaderSyncRecord> batch = fetchHeaderBatchFromAnyPeer(syncPeers, nextHeight, target,
                    expectedPreviousHash, runningChainWork);
            if (batch.isEmpty()) {
                String error = "Unable to download headers starting at height " + nextHeight;
                addRecentError(error);
                throw new IllegalStateException(error);
            }

            headerChain.addAll(batch);
            HeaderSyncRecord last = batch.getLast();
            acceptedHeight = last.height();
            expectedPreviousHash = last.hash();
            runningChainWork = last.chainWork();
            downloadedHeaders.addAndGet(batch.size());
            verifiedHeaders.addAndGet(batch.size());
            syncMessage.set("Verified header " + acceptedHeight);
            touchProgress();
        }

        return headerChain;
    }

    private List<HeaderSyncRecord> fetchHeaderBatchFromAnyPeer(
            List<Peer> syncPeers,
            int startHeight,
            Peer.ChainTip target,
            byte[] expectedPreviousHash,
            UInt256 previousChainWork
    ) {
        int stopHeight = startHeight + Math.min(target.height() - startHeight, MAX_HEADERS_PER_REQUEST - 1);
        int startIndex = Math.floorMod(peerCursor.getAndIncrement(), syncPeers.size());
        IllegalArgumentException lastValidationFailure = null;

        for (int attempt = 0; attempt <= BLOCK_FETCH_RETRY_TIMES; attempt++) {
            for (int i = 0; i < syncPeers.size(); i++) {
                Peer peer = syncPeers.get((startIndex + i) % syncPeers.size());
                String peerId = peerIdHex(peer);
                List<BlockHeadersPayload.Entry> headers = fetchHeaderBatchFromPeer(peerId, startHeight, stopHeight);
                if (!headers.isEmpty()) {
                    try {
                        List<HeaderSyncRecord> batch = validateAndStoreHeaderBatch(headers, startHeight, stopHeight,
                                target, expectedPreviousHash, previousChainWork);
                        trackerFor(peer).status.set("HEADERS");
                        return batch;
                    } catch (IllegalArgumentException invalidHeaders) {
                        lastValidationFailure = invalidHeaders;
                        trackerFor(peer).recordFailedRequest("Invalid headers starting at " + startHeight + ": "
                                + invalidHeaders.getMessage());
                        continue;
                    }
                }
                trackerFor(peer).recordFailedRequest("Headers starting at " + startHeight + " returned empty response");
            }

            if (attempt < BLOCK_FETCH_RETRY_TIMES) {
                try {
                    Thread.sleep(300L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
        }
        if (lastValidationFailure != null) {
            throw new IllegalStateException("All peers returned invalid headers starting at " + startHeight,
                    lastValidationFailure);
        }
        return List.of();
    }

    private List<HeaderSyncRecord> validateAndStoreHeaderBatch(List<BlockHeadersPayload.Entry> headers, int startHeight,
            int stopHeight, Peer.ChainTip target, byte[] expectedPreviousHash, UInt256 chainWork) {
        if (headers.size() > stopHeight - startHeight + 1) {
            throw new IllegalArgumentException("Header batch exceeds requested height " + stopHeight);
        }
        List<HeaderSyncRecord> records = new ArrayList<>(headers.size());
        for (BlockHeadersPayload.Entry entry : headers) {
            int expectedHeight = startHeight + records.size();
            if (entry == null || entry.height() != expectedHeight) {
                throw new IllegalArgumentException("Unexpected header height at " + expectedHeight);
            }
            BlockHeader header = entry.header();
            if (header == null || !Arrays.equals(expectedPreviousHash, header.getPreviousHash())) {
                throw new IllegalArgumentException("Header batch does not connect at " + expectedHeight);
            }
            expectedPreviousHash = header.calculateHash();
            if (expectedHeight == target.height() && !target.hashHex().equals(bytesToHex(expectedPreviousHash))) {
                throw new IllegalArgumentException("Verified headers do not reach the advertised tip");
            }
            chainWork = chainWork.add(UInt256.fromBigInteger(header.getWork()));
            records.add(new HeaderSyncRecord(expectedHeight, expectedPreviousHash, header, chainWork));
        }
        for (HeaderSyncRecord record : records) {
            if (!blockChainService.addBlockHeaderToStore(record.header(), record.height(), record.chainWork())) {
                throw new IllegalArgumentException("Header validation failed at height " + record.height());
            }
        }
        return records;
    }

    private List<BlockHeadersPayload.Entry> fetchHeaderBatchFromPeer(String peerId, int startHeight, int stopHeight) {
        Semaphore semaphore = peerSemaphores.computeIfAbsent(
                peerId,
                ignored -> new Semaphore(MAX_CONCURRENT_REQUESTS_PER_NODE)
        );

        boolean acquired = false;
        boolean headerSlotTracked = false;
        try {
            acquired = semaphore.tryAcquire(BLOCK_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                return List.of();
            }

            inFlightHeaders.incrementAndGet();
            headerSlotTracked = true;
            byte[] bytes = sendSyncRequest(
                    peerId,
                    ProtocolEnum.P2P_Query_Block_Headers,
                    BlockHeadersPayload.serializeRequest(startHeight, stopHeight, MAX_HEADERS_PER_REQUEST),
                    BLOCK_FETCH_TIMEOUT_SECONDS * 1000
            );
            if (bytes == null || bytes.length == 0) {
                return List.of();
            }

            P2PMessage message = P2PMessage.deserialize(bytes);
            if (message == null || message.getData() == null || message.getData().length == 0) {
                return List.of();
            }

            return BlockHeadersPayload.deserializeResponse(message.getData());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("Fetch headers failed: peer={}, startHeight={}, stopHeight={}", peerId, startHeight, stopHeight, e);
            return List.of();
        } finally {
            if (headerSlotTracked) {
                inFlightHeaders.decrementAndGet();
            }
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private byte[] syncBlocks(List<Peer> syncPeers, Landmark commonAncestor, Peer.ChainTip target) throws Exception {
        CompletionService<BlockFetchResult> completionService = new ExecutorCompletionService<>(syncExecutor);
        List<Future<BlockFetchResult>> futures = new ArrayList<>();
        NavigableMap<Integer, BlockFetchResult> pendingBlocks = new TreeMap<>();

        int firstHeight = commonAncestor.getHeight() + 1;
        int lastHeight = target.height();
        byte[] previousHash = commonAncestor.getHash();
        int nextSubmitHeight = firstHeight;
        int nextVerifyHeight = firstHeight;
        int inFlight = 0;

        try {
            while (nextVerifyHeight <= lastHeight) {
                while (nextSubmitHeight <= lastHeight && inFlight < MAX_IN_FLIGHT_BLOCKS) {
                    int height = nextSubmitHeight++;
                    Future<BlockFetchResult> future = completionService.submit(fetchTask(syncPeers, height));
                    futures.add(future);
                    inFlight++;
                    inFlightBlocks.incrementAndGet();
                }

                Future<BlockFetchResult> completed = completionService.poll(1, TimeUnit.SECONDS);
                if (completed == null) {
                    localHeight.set(getCurrentLocalHeight());
                    touchProgress();
                    continue;
                }

                inFlight--;
                inFlightBlocks.decrementAndGet();
                BlockFetchResult result = completed.get();
                if (!result.success()) {
                    throw new IllegalStateException("Failed to fetch block " + result.height() + ": " + result.error());
                }

                pendingBlocks.put(result.height(), result);
                while (pendingBlocks.containsKey(nextVerifyHeight)) {
                    BlockFetchResult pending = pendingBlocks.remove(nextVerifyHeight);
                    Block block = pending.block();
                    if (block.getHeight() != nextVerifyHeight
                            || !Arrays.equals(previousHash, block.getBlockHeader().getPreviousHash())
                            || (nextVerifyHeight == lastHeight && !target.hashHex().equals(bytesToHex(block.getHash())))) {
                        throw new IllegalStateException("Block " + nextVerifyHeight + " does not match the target branch");
                    }
                    verifyFetchedBlock(pending);
                    previousHash = block.getHash();
                    nextVerifyHeight++;
                }
            }
            return previousHash;
        } catch (Exception e) {
            for (Future<BlockFetchResult> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            throw e;
        }
    }

    private void syncBlocksByHeaders(List<Peer> syncPeers, List<HeaderSyncRecord> headerChain) throws Exception {
        CompletionService<BlockFetchResult> completionService = new ExecutorCompletionService<>(syncExecutor);
        List<Future<BlockFetchResult>> futures = new ArrayList<>();
        NavigableMap<Integer, BlockFetchResult> pendingBlocks = new TreeMap<>();

        int nextSubmitIndex = 0;
        int nextVerifyIndex = 0;
        int inFlight = 0;

        try {
            while (nextVerifyIndex < headerChain.size()) {
                while (nextSubmitIndex < headerChain.size() && inFlight < MAX_IN_FLIGHT_BLOCKS) {
                    HeaderSyncRecord record = headerChain.get(nextSubmitIndex++);
                    Future<BlockFetchResult> future = completionService.submit(fetchTask(syncPeers, record));
                    futures.add(future);
                    inFlight++;
                    inFlightBlocks.incrementAndGet();
                }

                Future<BlockFetchResult> completed = completionService.poll(1, TimeUnit.SECONDS);
                if (completed == null) {
                    localHeight.set(getCurrentLocalHeight());
                    touchProgress();
                    continue;
                }

                inFlight--;
                inFlightBlocks.decrementAndGet();
                BlockFetchResult result = completed.get();
                if (!result.success()) {
                    throw new IllegalStateException("Failed to fetch block " + result.height() + ": " + result.error());
                }

                pendingBlocks.put(result.height(), result);
                while (nextVerifyIndex < headerChain.size()) {
                    HeaderSyncRecord expected = headerChain.get(nextVerifyIndex);
                    BlockFetchResult pending = pendingBlocks.get(expected.height());
                    if (pending == null) {
                        break;
                    }
                    pendingBlocks.remove(expected.height());
                    verifyFetchedBlock(pending);
                    nextVerifyIndex++;
                }
            }
        } catch (Exception e) {
            for (Future<BlockFetchResult> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            throw e;
        }
    }

    private Callable<BlockFetchResult> fetchTask(List<Peer> syncPeers, int height) {
        return () -> fetchBlockFromAnyPeer(syncPeers, height);
    }

    private Callable<BlockFetchResult> fetchTask(List<Peer> syncPeers, HeaderSyncRecord record) {
        return () -> fetchBlockByHashFromAnyPeer(syncPeers, record);
    }

    private BlockFetchResult fetchBlockFromAnyPeer(List<Peer> syncPeers, int height) {
        int startIndex = Math.floorMod(peerCursor.getAndIncrement(), syncPeers.size());
        String lastPeerId = "";
        String lastError = "";

        for (int attempt = 0; attempt <= BLOCK_FETCH_RETRY_TIMES; attempt++) {
            for (int i = 0; i < syncPeers.size(); i++) {
                Peer peer = syncPeers.get((startIndex + i) % syncPeers.size());
                String peerId = peerIdHex(peer);
                lastPeerId = peerId;
                PeerSyncTracker tracker = trackerFor(peer);
                tracker.assign(height);

                long startNanos = System.nanoTime();
                Block block = fetchBlockFromPeer(peerId, height);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                if (block != null) {
                    downloadedBlocks.incrementAndGet();
                    tracker.recordDownloaded(height);
                    syncMessage.set("Downloaded block " + height + " from " + shortPeerId(peerId));
                    touchProgress();
                    return BlockFetchResult.success(height, peerId, block, elapsedMillis);
                }

                lastError = "empty response";
                tracker.recordFailedRequest("Block " + height + " returned empty response");
            }

            if (attempt < BLOCK_FETCH_RETRY_TIMES) {
                try {
                    Thread.sleep(300L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failedBlocks.incrementAndGet();
                    return BlockFetchResult.failed(height, lastPeerId, "interrupted");
                }
            }
        }

        failedBlocks.incrementAndGet();
        String error = "Unable to download block " + height + " after retries, lastError=" + lastError;
        addRecentError(error);
        return BlockFetchResult.failed(height, lastPeerId, error);
    }

    private BlockFetchResult fetchBlockByHashFromAnyPeer(List<Peer> syncPeers, HeaderSyncRecord record) {
        Block existing = blockChainService.getBlockByHash(record.hash());
        if (existing != null) {
            return BlockFetchResult.success(record.height(), "local", existing, 0L, record.hash());
        }

        int startIndex = Math.floorMod(peerCursor.getAndIncrement(), syncPeers.size());
        String lastPeerId = "";
        String lastError = "";

        for (int attempt = 0; attempt <= BLOCK_FETCH_RETRY_TIMES; attempt++) {
            for (int i = 0; i < syncPeers.size(); i++) {
                Peer peer = syncPeers.get((startIndex + i) % syncPeers.size());
                String peerId = peerIdHex(peer);
                lastPeerId = peerId;
                PeerSyncTracker tracker = trackerFor(peer);
                tracker.assign(record.height());

                long startNanos = System.nanoTime();
                Block block = fetchBlockByHashFromPeer(peerId, record.hash());
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                if (block != null && Arrays.equals(record.hash(), block.getHash())) {
                    downloadedBlocks.incrementAndGet();
                    tracker.recordDownloaded(record.height());
                    syncMessage.set("Downloaded block " + record.height() + " from " + shortPeerId(peerId));
                    touchProgress();
                    return BlockFetchResult.success(record.height(), peerId, block, elapsedMillis, record.hash());
                }

                lastError = block == null ? "empty response" : "hash mismatch";
                tracker.recordFailedRequest("Block " + record.height() + " " + lastError);
            }

            if (attempt < BLOCK_FETCH_RETRY_TIMES) {
                try {
                    Thread.sleep(300L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failedBlocks.incrementAndGet();
                    return BlockFetchResult.failed(record.height(), lastPeerId, "interrupted");
                }
            }
        }

        failedBlocks.incrementAndGet();
        String error = "Unable to download block " + record.height() + " by hash after retries, lastError=" + lastError;
        addRecentError(error);
        return BlockFetchResult.failed(record.height(), lastPeerId, error);
    }

    private void verifyFetchedBlock(BlockFetchResult result) {
        setStage("VERIFYING", "Verifying block " + result.height());
        try {
            if (result.expectedHash() != null && !Arrays.equals(result.expectedHash(), result.block().getHash())) {
                failedBlocks.incrementAndGet();
                throw new IllegalStateException("Block " + result.height() + " does not match verified header hash");
            }
            boolean verified = blockChainService.verifyBlock(result.block());
            if (!verified) {
                failedBlocks.incrementAndGet();
                PeerSyncTracker tracker = peerStates.get(result.peerId());
                if (tracker != null) {
                    tracker.recordFailedRequest("Block " + result.height() + " failed validation");
                }
                throw new IllegalStateException("Block " + result.height() + " failed validation");
            }

            verifiedBlocks.incrementAndGet();
            localHeight.set(getCurrentLocalHeight());
            PeerSyncTracker tracker = peerStates.get(result.peerId());
            if (tracker != null) {
                tracker.recordVerified(result.height());
            }
            syncMessage.set("Verified block " + result.height());
            syncStatus.set("DOWNLOADING");
            touchProgress();
        } catch (RuntimeException e) {
            addRecentError(e.getMessage());
            throw e;
        }
    }

    private Block fetchBlockFromPeer(String peerId, int height) {
        Semaphore semaphore = peerSemaphores.computeIfAbsent(
                peerId,
                ignored -> new Semaphore(MAX_CONCURRENT_REQUESTS_PER_NODE)
        );

        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(BLOCK_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Timed out waiting for peer request slot: peer={}, height={}", peerId, height);
                return null;
            }

            byte[] bytes = sendSyncRequest(
                    peerId,
                    ProtocolEnum.P2P_Query_Block_By_Height,
                    RocksDb.intToBytes(height),
                    BLOCK_FETCH_TIMEOUT_SECONDS * 1000
            );
            if (bytes == null || bytes.length == 0) {
                return null;
            }

            P2PMessage message = P2PMessage.deserialize(bytes);
            if (message == null || message.getData() == null || message.getData().length == 0) {
                return null;
            }

            return Block.deserialize(message.getData());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Fetch block failed: peer={}, height={}", peerId, height, e);
            return null;
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private Block fetchBlockByHashFromPeer(String peerId, byte[] hash) {
        Semaphore semaphore = peerSemaphores.computeIfAbsent(
                peerId,
                ignored -> new Semaphore(MAX_CONCURRENT_REQUESTS_PER_NODE)
        );

        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(BLOCK_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Timed out waiting for peer request slot: peer={}, hash={}", peerId, bytesToHex(hash));
                return null;
            }

            byte[] bytes = sendSyncRequest(
                    peerId,
                    ProtocolEnum.P2P_Query_Block_By_Hash,
                    hash,
                    BLOCK_FETCH_TIMEOUT_SECONDS * 1000
            );
            if (bytes == null || bytes.length == 0) {
                return null;
            }

            P2PMessage message = P2PMessage.deserialize(bytes);
            if (message == null || message.getData() == null || message.getData().length == 0) {
                return null;
            }

            return Block.deserialize(message.getData());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Fetch block by hash failed: peer={}, hash={}", peerId, bytesToHex(hash), e);
            return null;
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private Block fetchBlockFromPeerWithRetry(String peerId, int height) {
        for (int retry = 0; retry <= BLOCK_FETCH_RETRY_TIMES; retry++) {
            Block block = fetchBlockFromPeer(peerId, height);
            if (block != null) {
                return block;
            }
            if (retry < BLOCK_FETCH_RETRY_TIMES) {
                try {
                    Thread.sleep(300L * (retry + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    byte[] sendSyncRequest(String peerId, ProtocolEnum protocol, byte[] request, int timeoutMillis) {
        return staticSendData(peerId, protocol, request, timeoutMillis);
    }

    private Landmark queryCommonAncestor(List<Peer> peers, int maxAncestorHeight) {
        List<Landmark> localLandmarks = blockChainService.generateSyncLandmarks(maxAncestorHeight);
        byte[] payload = Landmark.serializeList(localLandmarks);

        for (Peer peer : peers) {
            String peerId = peerIdHex(peer);
            try {
                byte[] bytes = sendSyncRequest(
                        peerId,
                        ProtocolEnum.P2P_Query_Common_Ancestor,
                        payload,
                        BLOCK_FETCH_TIMEOUT_SECONDS * 1000
                );
                if (bytes == null || bytes.length == 0) {
                    trackerFor(peer).recordFailedRequest("Common ancestor returned empty response");
                    continue;
                }

                P2PMessage message = P2PMessage.deserialize(bytes);
                if (message == null || message.getData() == null || message.getData().length == 0) {
                    trackerFor(peer).recordFailedRequest("Common ancestor response is invalid");
                    continue;
                }

                Landmark commonAncestor = Landmark.deserialize(message.getData());
                if (commonAncestor.getHeight() <= maxAncestorHeight && localLandmarks.contains(commonAncestor)) {
                    trackerFor(peer).status.set("READY");
                    return commonAncestor;
                }
                trackerFor(peer).recordFailedRequest("Common ancestor was not in the local landmarks");
            } catch (Exception e) {
                trackerFor(peer).recordFailedRequest("Common ancestor query failed: " + e.getMessage());
                log.warn("Common ancestor query failed: peer={}", peerId, e);
            }
        }

        return null;
    }

    private Map<Peer.ChainTip, List<Peer>> selectSyncTargets(List<Peer> peers, UInt256 localWork) {
        return peers.stream()
                .map(SyncPeer::snapshot)
                .filter(candidate -> candidate.tip().height() > 0 && candidate.tip().hashHex() != null
                        && candidate.tip().hashHex().length() == 64
                        && candidate.tip().chainWork().compareTo(localWork) > 0)
                .sorted(SYNC_PEER_ORDER)
                .limit(MAX_SYNC_PEERS)
                .collect(Collectors.groupingBy(SyncPeer::tip, LinkedHashMap::new,
                        Collectors.mapping(SyncPeer::peer, Collectors.toList())));
    }

    @Override
    public int getCurrentLocalHeight() {
        Block tip = mainTipBlock;
        return tip == null ? 0 : tip.getHeight();
    }

    @Override
    public UInt256 getCurrentLocalChainWork() {
        Block tip = mainTipBlock;
        return tip == null ? UInt256.ZERO : tip.getChainWork();
    }

    @Override
    public int getBestNetworkHeight() {
        return Optional.ofNullable(routingTable.getOnlineNodes())
                .orElseGet(List::of)
                .stream()
                .filter(peer -> peer.getId() != null)
                .mapToInt(Peer::getHeight)
                .max()
                .orElse(getCurrentLocalHeight());
    }

    @Override
    public Peer getBestPeer() {
        return getConnectedPeers().stream().map(SyncPeer::snapshot)
                .min(SYNC_PEER_ORDER).map(SyncPeer::peer).orElse(null);
    }

    @Override
    public List<Peer> getConnectedPeers() {
        return Optional.ofNullable(routingTable.getOnlineNodes())
                .orElseGet(List::of)
                .stream()
                .filter(peer -> peer.getId() != null && peer.isOnline())
                .collect(Collectors.toList());
    }

    @Override
    public Block getLatestBlockFromOnlinePeers() {
        Peer bestPeer = getBestPeer();
        if (bestPeer == null || bestPeer.getId() == null) {
            log.warn("No online peer is available");
            return null;
        }

        return fetchBlockFromPeerWithRetry(peerIdHex(bestPeer), bestPeer.getHeight());
    }

    @Override
    public SyncProgress getSyncProgress() {
        List<Peer> connectedPeers = getConnectedPeers();
        refreshPeerStates(connectedPeers);
        connectedPeerCount.set(connectedPeers.size());
        updateBestPeerFields();

        boolean headerStage = "HEADERS_FIRST".equals(syncMode.get())
                && ("DOWNLOADING_HEADERS".equals(syncStatus.get()) || "VERIFYING_HEADERS".equals(syncStatus.get()));
        int total = headerStage ? totalHeaders.get() : totalBlocks.get();
        int verified = headerStage ? verifiedHeaders.get() : verifiedBlocks.get();
        double percent = total <= 0 ? ("COMPLETED".equals(syncStatus.get()) ? 100.0 : 0.0)
                : Math.min(100.0, verified * 100.0 / total);

        long startedAt = startedAtMillis.get();
        long now = System.currentTimeMillis();
        long elapsedMillis = startedAt > 0 ? Math.max(1L, now - startedAt) : 0L;
        double blocksPerSecond = elapsedMillis > 0 ? verified / (elapsedMillis / 1000.0) : 0.0;
        int remaining = Math.max(0, total - verified);
        long estimatedSecondsRemaining = blocksPerSecond > 0.0
                ? (long) Math.ceil(remaining / blocksPerSecond)
                : -1L;

        return new SyncProgress(
                syncRunning.get(),
                syncStatus.get(),
                syncMode.get(),
                syncMessage.get(),
                startedAtMillis.get(),
                updatedAtMillis.get(),
                finishedAtMillis.get(),
                localHeightAtStart.get(),
                localHeight.get(),
                networkHeight.get(),
                commonAncestorHeight.get(),
                startHeight.get(),
                targetHeight.get(),
                totalBlocks.get(),
                totalHeaders.get(),
                downloadedBlocks.get(),
                downloadedHeaders.get(),
                verifiedBlocks.get(),
                verifiedHeaders.get(),
                failedBlocks.get(),
                inFlightBlocks.get(),
                inFlightHeaders.get(),
                percent,
                blocksPerSecond,
                estimatedSecondsRemaining,
                connectedPeerCount.get(),
                syncPeerCount.get(),
                bestPeerId.get(),
                bestPeerHeight.get(),
                List.copyOf(recentErrors),
                getSyncPeerStates()
        );
    }

    @Override
    public List<SyncPeerState> getSyncPeerStates() {
        List<Peer> connectedPeers = getConnectedPeers();
        refreshPeerStates(connectedPeers);

        Set<String> onlineIds = connectedPeers.stream()
                .map(this::peerIdHex)
                .collect(Collectors.toCollection(HashSet::new));

        return peerStates.values().stream()
                .filter(tracker -> onlineIds.contains(tracker.peerId) || tracker.hasActivity())
                .map(tracker -> tracker.snapshot(onlineIds.contains(tracker.peerId)))
                .sorted(Comparator
                        .comparing(SyncPeerState::online).reversed()
                        .thenComparing(SyncPeerState::height, Comparator.reverseOrder())
                        .thenComparing(SyncPeerState::peerId))
                .toList();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down block sync service");
        Future<?> task = currentSyncTask;
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }

        if (!syncExecutor.isShutdown()) {
            syncExecutor.shutdown();
            try {
                if (!syncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    syncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                syncExecutor.shutdownNow();
            }
        }

        peerSemaphores.clear();
        peerStates.clear();
        recentErrors.clear();
    }

    private void resetProgress() {
        long now = System.currentTimeMillis();
        peerCursor.set(0);
        peerStates.clear();
        recentErrors.clear();
        syncStatus.set("STARTING");
        syncMessage.set("Starting block sync");
        bestPeerId.set("");
        bestPeerHeight.set(0);
        localHeightAtStart.set(getCurrentLocalHeight());
        localHeight.set(getCurrentLocalHeight());
        networkHeight.set(getBestNetworkHeight());
        resetDownloadProgress();
        targetHeight.set(localHeight.get());
        connectedPeerCount.set(0);
        syncPeerCount.set(0);
        startedAtMillis.set(now);
        updatedAtMillis.set(now);
        finishedAtMillis.set(0L);
    }

    private void resetDownloadProgress() {
        commonAncestorHeight.set(0);
        startHeight.set(0);
        totalBlocks.set(0);
        totalHeaders.set(0);
        downloadedBlocks.set(0);
        downloadedHeaders.set(0);
        verifiedBlocks.set(0);
        verifiedHeaders.set(0);
        failedBlocks.set(0);
        inFlightBlocks.set(0);
        inFlightHeaders.set(0);
    }

    private void markFailed(String message, Exception e) {
        syncStatus.set("FAILED");
        syncMessage.set(message);
        addRecentError(message);
        finishedAtMillis.set(System.currentTimeMillis());
        log.error(message, e);
    }

    private void setStage(String status, String message) {
        syncStatus.set(status);
        syncMessage.set(message);
        touchProgress();
    }

    private void touchProgress() {
        updatedAtMillis.set(System.currentTimeMillis());
    }

    private void addRecentError(String error) {
        if (error == null || error.isBlank()) {
            return;
        }
        recentErrors.addFirst(System.currentTimeMillis() + " " + error);
        while (recentErrors.size() > RECENT_ERROR_LIMIT) {
            recentErrors.pollLast();
        }
        touchProgress();
    }

    private void updateBestPeerFields() {
        Peer bestPeer = getBestPeer();
        if (bestPeer == null || bestPeer.getId() == null) {
            bestPeerId.set("");
            bestPeerHeight.set(0);
            return;
        }
        bestPeerId.set(peerIdHex(bestPeer));
        bestPeerHeight.set(bestPeer.getHeight());
    }

    private void refreshPeerStates(List<Peer> peers) {
        for (Peer peer : peers) {
            trackerFor(peer).updatePeer(peer);
        }
    }

    private PeerSyncTracker trackerFor(Peer peer) {
        String peerId = peerIdHex(peer);
        return peerStates.computeIfAbsent(peerId, ignored -> new PeerSyncTracker(peer));
    }

    private String peerIdHex(Peer peer) {
        return bytesToHex(peer.getId());
    }

    private String shortPeerId(String peerId) {
        if (peerId == null || peerId.length() <= 12) {
            return peerId == null ? "" : peerId;
        }
        return peerId.substring(0, 12);
    }

    private record SyncPeer(Peer peer, Peer.ChainTip tip, long lastSeen) {
        private static SyncPeer snapshot(Peer peer) {
            return new SyncPeer(peer, peer.chainTip(), peer.getLastSeen());
        }
    }

    private record BlockFetchResult(
            int height,
            String peerId,
            Block block,
            String error,
            long elapsedMillis,
            byte[] expectedHash
    ) {
        static BlockFetchResult success(int height, String peerId, Block block, long elapsedMillis) {
            return new BlockFetchResult(height, peerId, block, "", elapsedMillis, null);
        }

        static BlockFetchResult success(int height, String peerId, Block block, long elapsedMillis, byte[] expectedHash) {
            return new BlockFetchResult(height, peerId, block, "", elapsedMillis, expectedHash);
        }

        static BlockFetchResult failed(int height, String peerId, String error) {
            return new BlockFetchResult(height, peerId, null, error, 0L, null);
        }

        boolean success() {
            return block != null;
        }
    }

    private record HeaderSyncRecord(
            int height,
            byte[] hash,
            BlockHeader header,
            UInt256 chainWork
    ) {
    }

    private static final class PeerSyncTracker {
        private final String peerId;
        private final AtomicReference<String> status = new AtomicReference<>("CONNECTED");
        private final AtomicInteger assignedStartHeight = new AtomicInteger(Integer.MAX_VALUE);
        private final AtomicInteger assignedEndHeight = new AtomicInteger(Integer.MIN_VALUE);
        private final AtomicInteger downloadedBlocks = new AtomicInteger(0);
        private final AtomicInteger verifiedBlocks = new AtomicInteger(0);
        private final AtomicInteger failedRequests = new AtomicInteger(0);
        private final AtomicInteger lastSyncedHeight = new AtomicInteger(Integer.MIN_VALUE);
        private final AtomicReference<String> lastError = new AtomicReference<>("");
        private final AtomicLong lastUpdatedAtMillis = new AtomicLong(System.currentTimeMillis());

        private volatile String address;
        private volatile int port;
        private volatile int height;
        private volatile String hashHex;
        private volatile String chainWorkDecimal;

        private PeerSyncTracker(Peer peer) {
            this.peerId = bytesToHex(peer.getId());
            updatePeer(peer);
        }

        private void updatePeer(Peer peer) {
            this.address = peer.getAddress();
            this.port = peer.getPort();
            Peer.ChainTip tip = peer.chainTip();
            this.height = tip.height();
            this.hashHex = tip.hashHex();
            this.chainWorkDecimal = tip.chainWork().toString();
            this.lastUpdatedAtMillis.set(System.currentTimeMillis());
        }

        private void assign(int blockHeight) {
            assignedStartHeight.accumulateAndGet(blockHeight, Math::min);
            assignedEndHeight.accumulateAndGet(blockHeight, Math::max);
            status.set("DOWNLOADING");
            lastUpdatedAtMillis.set(System.currentTimeMillis());
        }

        private void recordDownloaded(int blockHeight) {
            downloadedBlocks.incrementAndGet();
            lastSyncedHeight.accumulateAndGet(blockHeight, Math::max);
            status.set("DOWNLOADING");
            lastUpdatedAtMillis.set(System.currentTimeMillis());
        }

        private void recordVerified(int blockHeight) {
            verifiedBlocks.incrementAndGet();
            lastSyncedHeight.accumulateAndGet(blockHeight, Math::max);
            status.set("SYNCING");
            lastUpdatedAtMillis.set(System.currentTimeMillis());
        }

        private void recordFailedRequest(String error) {
            failedRequests.incrementAndGet();
            lastError.set(error == null ? "" : error);
            status.set("DEGRADED");
            lastUpdatedAtMillis.set(System.currentTimeMillis());
        }

        private boolean hasActivity() {
            return downloadedBlocks.get() > 0
                    || verifiedBlocks.get() > 0
                    || failedRequests.get() > 0
                    || assignedStartHeight.get() != Integer.MAX_VALUE;
        }

        private SyncPeerState snapshot(boolean online) {
            Integer assignedStart = assignedStartHeight.get() == Integer.MAX_VALUE ? null : assignedStartHeight.get();
            Integer assignedEnd = assignedEndHeight.get() == Integer.MIN_VALUE ? null : assignedEndHeight.get();
            Integer lastSynced = lastSyncedHeight.get() == Integer.MIN_VALUE ? null : lastSyncedHeight.get();

            return new SyncPeerState(
                    peerId,
                    address,
                    port,
                    online,
                    height,
                    hashHex,
                    chainWorkDecimal,
                    online ? status.get() : "DISCONNECTED",
                    assignedStart,
                    assignedEnd,
                    lastSynced,
                    downloadedBlocks.get(),
                    verifiedBlocks.get(),
                    failedRequests.get(),
                    lastError.get(),
                    lastUpdatedAtMillis.get()
            );
        }
    }
}
