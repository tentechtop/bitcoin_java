package com.bit.coin.blockchain;

import com.bit.coin.config.ParamsConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.structure.block.*;
import com.bit.coin.structure.tx.*;
import com.bit.coin.utils.*;
import org.bitcoinj.base.Base58;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.*;

import static com.bit.coin.database.rocksDb.TableEnum.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ChainStateStoreIT {
    @TempDir Path directory;
    private RocksDb database;
    private ChainStateStore store;
    private Block tip;
    private final KeyPair owner = Ed25519Signer.generateKeyPair();
    private final ParamsConfig.Params params = ParamsConfig.RegressionNetParams;

    @BeforeEach void open() {
        database = new RocksDb();
        SystemConfig config = new SystemConfig();
        config.setPath(directory.resolve("chain").toString());
        assertTrue(database.createDatabase(config));
        store = new ChainStateStore(database, this::block);
        tip = BlockChainServiceImpl.createGenesisBlock(params);
        store.initialize(tip, params);
    }

    @AfterEach void close() { database.close(); BlockChainServiceImpl.mainTipBlock = null; }

    private Block block(byte[] hash) {
        byte[] body = database.get(TBLOCK_BODY, hash);
        if (body == null) return null;
        Block block = Block.buildBlock(BlockHeader.deserialize(database.get(TBLOCK_HEADER, hash)), BlockBody.deserialize(body));
        block.setChainWork(UInt256.fromBytes(database.get(HASH_TO_CHAIN_WORK, hash)));
        return block;
    }

    private Block next(Block parent, Transaction... transactions) {
        String address = Base58.encode(Ed25519Signer.extractPublicKeyCore(owner.getPublic()));
        Block candidate = new Block();
        candidate.setHeight(parent.getHeight() + 1);
        List<Transaction> txs = new ArrayList<>();
        txs.add(BlockChainServiceImpl.createCoinBaseTransaction(address, candidate.getHeight(), 0, params));
        txs.addAll(Arrays.asList(transactions));
        candidate.setTransactions(txs);
        candidate.setTxCount(txs.size());
        BlockHeader header = new BlockHeader(1, parent.getHash(), BlockChainServiceImpl.calculateMerkleRoot(txs),
                parent.getBlockHeader().getTime() + 1, (int) params.getPowLimitBits(), 0);
        while (!header.verifyProofOfWork()) header.setNonce(header.getNonce() + 1);
        candidate.setBlockHeader(header);
        return candidate;
    }

    private void accept(Block candidate) {
        if (store.accept(candidate, tip, params).mainChanged()) tip = candidate;
    }

    private Transaction spend(UTXO source, long amount) {
        Transaction tx = new Transaction();
        tx.setVersion(1);
        TxInput input = new TxInput();
        input.setTxId(source.getTxId());
        input.setIndex(source.getIndex());
        input.setScriptSig(new byte[97]);
        tx.getInputs().add(input);
        TxOutput output = new TxOutput();
        output.setValue(amount);
        output.setScriptPubKey(source.getScript());
        tx.getOutputs().add(output);
        tx.setUtxoMap(Map.of(SerializeUtils.bytesToHex(source.getKey()), source));
        tx.sign(Ed25519Signer.extractPrivateKeyCore(owner.getPrivate()), Ed25519Signer.extractPublicKeyCore(owner.getPublic()));
        tx.setTxId(tx.calculateTxId());
        return tx;
    }

    @Test void dependentTransactionsAndReorgRestoreExactCoinbaseMetadata() {
        Block first = next(tip);
        accept(first);
        UTXO source = first.getUTXOs().getFirst();
        while (tip.getHeight() < 10) accept(next(tip));
        Block ancestor = tip;
        Transaction a = spend(source, 90_000);
        UTXO intermediate = new UTXO();
        intermediate.setTxId(a.getTxId());
        intermediate.setValue(90_000);
        intermediate.setScript(source.getScript());
        Transaction b = spend(intermediate, 80_000);
        Block old = next(tip, a, b);
        accept(old);
        assertNull(database.get(TUTXO, source.getKey()));
        assertNull(database.get(TUTXO, intermediate.getKey()));
        assertNotNull(database.get(TUTXO, UTXO.getKey(b.getTxId(), 0)));

        Block fork = next(ancestor);
        accept(fork);
        assertArrayEquals(old.getHash(), tip.getHash());
        Block winner = next(fork);
        accept(winner);
        UTXO restored = UTXO.deserialize(database.get(TUTXO, source.getKey()));
        assertTrue(restored.isCoinbase());
        assertEquals(1, restored.getHeight());
        assertEquals(source.getValue(), restored.getValue());
        assertNotNull(database.get(ADDR_TO_UTXO, restored.getAddressKey()));
        assertNull(database.get(TUTXO, UTXO.getKey(b.getTxId(), 0)));
        assertNull(database.get(TX_TO_BLOCK, b.getTxId()));
        assertArrayEquals(winner.getHash(), database.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
    }

    @Test void coinbaseMaturityUsesCandidateHeightIncludingBoundary() {
        Block first = next(tip);
        accept(first);
        Transaction spend = spend(first.getUTXOs().getFirst(), 10_000);
        while (tip.getHeight() < 9) accept(next(tip));
        assertThrows(IllegalArgumentException.class, () -> accept(next(tip, spend)));
        accept(next(tip));
        assertDoesNotThrow(() -> accept(next(tip, spend)));
    }

    @Test void commitFailureDoesNotPublishAnyChainState() {
        Block candidate = next(tip);
        DataBase failing = spy(database);
        doReturn(false).when(failing).dataTransaction(anyList());
        ChainStateStore failingStore = new ChainStateStore(failing, this::block);
        assertThrows(IllegalStateException.class, () -> failingStore.accept(candidate, tip, params));
        assertNull(database.get(TBLOCK_BODY, candidate.getHash()));
        assertNull(database.get(HEIGHT_TO_HASH, 1));
        assertArrayEquals(tip.getHash(), database.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
    }

    @ParameterizedTest
    @ValueSource(ints = {512, 513})
    void losingForkAtReorgBudgetBoundaryDoesNotDisableHealthyNode(int height) {
        Block genesis = tip;
        while (tip.getHeight() < height) accept(next(tip));
        Block fork = next(genesis);
        fork.getBlockHeader().setTime(fork.getBlockHeader().getTime() + 1);
        while (!fork.getBlockHeader().verifyProofOfWork(false)) {
            fork.getBlockHeader().setNonce(fork.getBlockHeader().getNonce() + 1);
        }
        fork.setHash(fork.getBlockHeader().calculateHash());

        BlockChainServiceImpl chain = queryService();
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "utxoCache", mock(com.bit.coin.cache.UTXOCache.class));
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "txPool", mock(com.bit.coin.txpool.TxPool.class));
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "routingTable", mock(com.bit.coin.p2p.kad.RoutingTable.class));

        assertEquals(height == 512, chain.verifyBlock(fork));
        assertTrue(chain.isStateHealthy(), "A candidate's processing budget must not poison local state");
        assertEquals(height == 512 ? 0 : 1, chain.rejectedBlocks());
        assertEquals(height == 512, database.get(TBLOCK_BODY, fork.getHash()) != null);
        assertArrayEquals(tip.getHash(), database.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
        for (UTXO output : tip.getUTXOs()) assertNotNull(database.get(TUTXO, output.getKey()));

        Block successor = next(tip);
        assertTrue(chain.verifyBlock(successor), "A normal extension must still be accepted");
        assertTrue(chain.isStateHealthy());
        assertArrayEquals(successor.getHash(), database.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
        assertEquals(height + 1, BlockChainServiceImpl.mainTipBlock.getHeight());
    }

    @Test void realCommitFailureStillDisablesNodeWithoutPublishingState() {
        Block candidate = next(tip);
        BlockChainServiceImpl chain = queryService();
        DataBase failing = spy(database);
        doReturn(false).when(failing).dataTransaction(anyList());
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "dataBase", failing);

        assertFalse(chain.verifyBlock(candidate));
        assertFalse(chain.isStateHealthy());
        assertNull(database.get(TBLOCK_BODY, candidate.getHash()));
        assertArrayEquals(tip.getHash(), database.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
        assertArrayEquals(tip.getHash(), BlockChainServiceImpl.mainTipBlock.getHash());
    }

    @Test void restartAndIndependentDatabaseInstancesPreserveState() {
        accept(next(tip));
        byte[] hash = tip.getHash();
        try (RocksDb other = new RocksDb()) {
            SystemConfig config = new SystemConfig();
            config.setPath(directory.resolve("other").toString());
            assertTrue(other.createDatabase(config));
            assertNull(other.get(TBLOCK_BODY, hash));
            assertNotNull(database.get(TBLOCK_BODY, hash));
        }
        database.close();
        SystemConfig config = new SystemConfig();
        config.setPath(directory.resolve("chain").toString());
        assertTrue(database.createDatabase(config));
        assertArrayEquals(hash, block(hash).getHash());
        assertNotNull(UtxoTransition.deserialize(database.get(BLOCK_UNDO, hash)));
    }

    @Test void malformedDifficultyAndExcessiveCoinbaseAreRejected() {
        Block invalid = next(tip);
        invalid.getBlockHeader().setBits(0x2100ffff);
        assertThrows(IllegalArgumentException.class, () -> accept(invalid));
        Block excessive = next(tip);
        excessive.getTransactions().getFirst().getOutputs().getFirst().setValue(50L * 100_000_000 + 1);
        excessive.getTransactions().getFirst().setTxId(excessive.getTransactions().getFirst().calculateTxId());
        excessive.getBlockHeader().setMerkleRoot(BlockChainServiceImpl.calculateMerkleRoot(excessive.getTransactions()));
        while (!excessive.getBlockHeader().verifyProofOfWork()) excessive.getBlockHeader().setNonce(excessive.getBlockHeader().getNonce() + 1);
        excessive.setHash(excessive.getBlockHeader().calculateHash());
        assertThrows(IllegalArgumentException.class, () -> accept(excessive));
    }

    @Test void checkpointRestoreAndReindexPreserveTipAndRejectCorruption() throws Exception {
        accept(next(tip)); accept(next(tip));
        byte[] expected = tip.getHash();
        database.close();
        String source = directory.resolve("chain").toString();
        Path backup = directory.resolve("backup"), restored = directory.resolve("restored"), rebuilt = directory.resolve("rebuilt");
        com.bit.coin.ops.DatabaseTool.main(new String[]{"checkpoint", source, backup.toString(), "RegressionNetParams"});
        com.bit.coin.ops.DatabaseTool.verifyManifest(backup);
        com.bit.coin.ops.DatabaseTool.main(new String[]{"restore", backup.toString(), restored.toString(), "RegressionNetParams"});
        com.bit.coin.ops.DatabaseTool.main(new String[]{"reindex", source, rebuilt.toString(), "RegressionNetParams"});
        for (Path path : List.of(restored, rebuilt)) {
            try (RocksDb check = new RocksDb()) {
                SystemConfig config = new SystemConfig(); config.setPath(path.toString()); assertTrue(check.createDatabase(config));
                assertArrayEquals(expected, check.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
                assertNotNull(check.get(TUTXO, tip.getUTXOs().getFirst().getKey()));
            }
        }
        java.nio.file.Files.writeString(backup.resolve("CURRENT"), "corrupted");
        assertThrows(IllegalStateException.class, () -> com.bit.coin.ops.DatabaseTool.verifyManifest(backup));
        assertThrows(IllegalArgumentException.class, () -> com.bit.coin.ops.DatabaseTool.main(new String[]{"checkpoint", source, rebuilt.toString(), "RegressionNetParams"}));
    }

    @Test void transactionBuffersCommitOnceAndOwnTheirBytes() {
        String id = database.beginTransaction();
        byte[] key = {7}, value = {8};
        database.addToTransaction(id, new RocksDb.DbOperation(ACCOUNT, key, value, RocksDb.DbOperation.OpType.INSERT));
        key[0] = 9; value[0] = 10;
        assertTrue(database.commitTransaction(id));
        assertFalse(database.commitTransaction(id));
        assertFalse(database.rollbackTransaction(id));
        assertArrayEquals(new byte[]{8}, database.get(ACCOUNT, new byte[]{7}));
        assertNull(database.get(ACCOUNT, new byte[]{9}));
    }

    @Test void genesisVectorsAreDeterministicAndNetworkSpecific() {
        Set<String> hashes = new HashSet<>();
        for (ParamsConfig.Params network : List.of(ParamsConfig.MainNetParams, ParamsConfig.TestNet3Params, params)) {
            Block genesis = BlockChainServiceImpl.createGenesisBlock(network);
            assertTrue(genesis.getBlockHeader().verifyProofOfWork());
            assertTrue(hashes.add(SerializeUtils.bytesToHex(genesis.getHash())));
            System.out.println("GENESIS " + network.getName() + " nonce=" + genesis.getBlockHeader().getNonce() + " hash=" + SerializeUtils.bytesToHex(genesis.getHash()));
        }
    }
    private BlockChainServiceImpl queryService() {
        BlockChainServiceImpl chain = new BlockChainServiceImpl();
        SystemConfig config = new SystemConfig(); config.setNetParams("RegressionNetParams");
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "dataBase", database);
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "config", config);
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "stateHealthy", true);
        BlockChainServiceImpl.mainTipBlock = tip;
        return chain;
    }

    @Test void headerOnlySyncVerifiesHeightWorkAndAncestors() {
        BlockChainServiceImpl chain = queryService();
        Block first = next(tip);
        UInt256 work = UInt256.fromBigInteger(tip.getChainWork().toBigInteger().add(first.getBlockHeader().getWork()));
        assertFalse(chain.addBlockHeaderToStore(first.getBlockHeader(), 2, work));
        assertFalse(chain.addBlockHeaderToStore(first.getBlockHeader(), 1, UInt256.fromBigInteger(java.math.BigInteger.ONE)));
        assertTrue(chain.addBlockHeaderToStore(first.getBlockHeader(), 1, work));
        assertNull(database.get(TBLOCK_BODY, first.getHash()));
        Block second = next(first);
        UInt256 secondWork = UInt256.fromBigInteger(work.toBigInteger().add(second.getBlockHeader().getWork()));
        assertTrue(chain.addBlockHeaderToStore(second.getBlockHeader(), 2, secondWork));
        assertEquals(2, chain.getHeaderContext(second.getHash()).getHeight());
        assertArrayEquals(tip.getHash(), database.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
    }

    @Test @SuppressWarnings("unchecked")
    void balanceUsesDurableIndexSurvivesEvictionAndPropagatesCorruption() {
        accept(next(tip));
        UTXO expected = tip.getUTXOs().getFirst();
        while (tip.getHeight() < 10) accept(next(tip));
        BlockChainServiceImpl chain = queryService();
        var cache = new com.bit.coin.cache.UTXOCacheImpl();
        var chains = mock(org.springframework.beans.factory.ObjectProvider.class);
        var pools = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(chains.getObject()).thenReturn(chain);
        when(pools.getObject()).thenReturn(mock(com.bit.coin.txpool.TxPool.class));
        SystemConfig config = new SystemConfig(); config.setNetParams("RegressionNetParams");
        org.springframework.test.util.ReflectionTestUtils.setField(cache, "dataBase", database);
        org.springframework.test.util.ReflectionTestUtils.setField(cache, "config", config);
        org.springframework.test.util.ReflectionTestUtils.setField(cache, "chains", chains);
        org.springframework.test.util.ReflectionTestUtils.setField(cache, "pools", pools);
        String address = Base58.encode(Ed25519Signer.extractPublicKeyCore(owner.getPublic()));
        long balance = (long) cache.getBalance(address).get("total");
        assertEquals(expected.getValue(), balance);
        UTXO mutable = cache.getUTXO(expected.getKey()); mutable.setValue(0); mutable.getScript()[0] ^= 1;
        assertEquals(expected.getValue(), cache.getUTXO(expected.getKey()).getValue());
        cache.invalidateAll();
        assertEquals(balance, cache.getBalance(address).get("total"));
        database.delete(TUTXO, expected.getKey());
        assertThrows(IllegalStateException.class, () -> cache.getBalance(address));
        // A failed callback must still release the RocksDB read lock.
        database.insert(ACCOUNT, new byte[]{42}, new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> cache.getBalance("invalid!"));
    }

    @Test void lowDiskSpaceRefusesAtomicWritesWithoutPublishingState() {
        Block candidate = next(tip);
        database.setMinFreeDiskBytes(Long.MAX_VALUE);
        assertFalse(database.checkHealth());
        assertThrows(IllegalStateException.class, () -> accept(candidate));
        assertNull(database.get(TBLOCK_BODY, candidate.getHash()));
        assertEquals(1, database.failedBatches());
        assertArrayEquals(tip.getHash(), database.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
    }

    @Test void reindexRepairsDerivedIndexesAndUndoRecordsFromBlockAncestry() throws Exception {
        accept(next(tip)); accept(next(tip));
        byte[] expected = tip.getHash();
        UTXO output = tip.getUTXOs().getFirst();
        database.delete(HEIGHT_TO_HASH, RocksDb.intToBytes(tip.getHeight()));
        database.delete(BLOCK_UNDO, expected);
        database.delete(HASH_TO_CHAIN_WORK, expected);
        database.delete(ADDR_TO_UTXO, output.getAddressKey());
        database.close();
        Path restored = directory.resolve("repair");
        com.bit.coin.ops.DatabaseTool.main(new String[]{"reindex", directory.resolve("chain").toString(), restored.toString(), "RegressionNetParams"});
        try (RocksDb check = new RocksDb()) {
            SystemConfig config = new SystemConfig(); config.setPath(restored.toString()); assertTrue(check.createDatabase(config));
            assertArrayEquals(expected, check.get(HEIGHT_TO_HASH, 2));
            assertNotNull(check.get(BLOCK_UNDO, expected));
            assertNotNull(check.get(HASH_TO_CHAIN_WORK, expected));
            assertNotNull(check.get(ADDR_TO_UTXO, output.getAddressKey()));
        }
    }

    @Test void arbitraryScriptCannotPoisonPublicKeyAddressPrefixes() {
        Block first = next(tip); accept(first);
        while (tip.getHeight() < 10) accept(next(tip));
        UTXO source = first.getUTXOs().getFirst();
        Transaction transaction = spend(source, 90_000);
        transaction.setVersion(101);
        byte[] script = Arrays.copyOf(source.getScript(), 21); script[20] = 0x51;
        transaction.getOutputs().getFirst().setScriptPubKey(script);
        transaction.sign(Ed25519Signer.extractPrivateKeyCore(owner.getPrivate()), Ed25519Signer.extractPublicKeyCore(owner.getPublic()));
        transaction.setTxId(transaction.calculateTxId());
        accept(next(tip, transaction));
        byte[] outpoint = UTXO.getKey(transaction.getTxId(), 0);
        assertArrayEquals(script, UTXO.deserialize(database.get(TUTXO, outpoint)).getScript());
        database.iterateByPrefix(ADDR_TO_UTXO, source.getScript(), (key, value) -> { assertEquals(56, key.length); return true; });
        database.iterateByPrefix(ADDR_TO_TX, source.getScript(), (key, value) -> { assertEquals(52, key.length); return true; });
        BlockChainServiceImpl chain = queryService();
        String address = Base58.encode(Ed25519Signer.extractPublicKeyCore(owner.getPublic()));
        assertEquals(tip.getHeight(), chain.utxoPage(address, 100, null, null).get("height"));
    }

}
