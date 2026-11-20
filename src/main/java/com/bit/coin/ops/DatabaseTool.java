package com.bit.coin.ops;

import com.bit.coin.blockchain.*;
import com.bit.coin.config.*;
import com.bit.coin.database.rocksDb.*;
import com.bit.coin.structure.block.*;
import com.bit.coin.utils.*;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static com.bit.coin.database.rocksDb.TableEnum.*;

/** 离线数据库维护工具，保留源数据库并禁止覆盖已有的目标路径。 */
public final class DatabaseTool {

    private static final String MANIFEST = "backup-sha256.txt";  // 备份目录中文件摘要清单的名称，恢复前据此核验文件

    private DatabaseTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: DatabaseTool checkpoint|restore|reindex SOURCE NEW_DESTINATION MainNetParams|TestNet3Params|RegressionNetParams");
        }
        Path source = Path.of(args[1]).toRealPath();
        Path destination = Path.of(args[2]).toAbsolutePath().normalize();
        if (Files.exists(destination) || destination.startsWith(source)
                || !Files.isRegularFile(source.resolve("CURRENT"))) {
            throw new IllegalArgumentException("Source must be a database; destination must be new and outside it");
        }
        Files.createDirectories(destination.getParent());
        destination = destination.getParent().toRealPath().resolve(destination.getFileName());
        if (Files.exists(destination) || destination.startsWith(source)) {
            throw new IllegalArgumentException("Destination resolves inside the source or already exists");
        }
        SystemConfig config = config(source, args[3]);
        if (args[0].equals("restore")) {
            verifyManifest(source);
            Files.createDirectory(destination);
            try (var files = Files.list(source)) {
                for (Path file : files.toList()) {
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                        throw new IllegalArgumentException("Unexpected backup entry");
                    }
                    Files.copy(file, destination.resolve(file.getFileName()));
                }
            }
            verifyManifest(destination);
            try (RocksDb restored = open(config(destination, args[3]))) {
                validateMetadata(restored, config.getParams());
            }
        } else if (args[0].equals("checkpoint") || args[0].equals("reindex")) {
            try (RocksDb original = open(config)) {
                if (args[0].equals("checkpoint")) {
                    validateMetadata(original, config.getParams());
                    original.checkpoint(destination);
                    writeManifest(destination);
                } else {
                    rebuild(original, destination, args[3], config.getParams());
                }
            }
        } else {
            throw new IllegalArgumentException("Unknown maintenance command");
        }
        System.out.println(args[0] + " completed: " + destination);
    }

    /** 沿源链顶回溯并记录祖先，再从创世块正向重放到目标库。 */
    private static void rebuild(RocksDb original, Path destination, String network, ParamsConfig.Params params)
            throws Exception {
        if (!Arrays.equals(original.get(CHAIN, ChainStateStore.REVISION_KEY),
                RocksDb.intToBytes(ConsensusRules.REVISION))) {
            throw new IllegalStateException("Incompatible consensus revision");
        }
        byte[] expectedTip = new ChainStateStore(original, hash -> block(original, hash)).bestStoredHash(params);
        byte[] genesis = BlockChainServiceImpl.createGenesisBlock(params).getHash();
        Path journal = Files.createTempFile(destination.getParent(), "reindex-ancestry-", ".bin");
        try (var path = new java.io.RandomAccessFile(journal.toFile(), "rw")) {
            byte[] cursor = expectedTip;
            int count = 0;
            while (!Arrays.equals(cursor, genesis)) {
                if (cursor == null || cursor.length != 32 || ++count > 10_000_000) {
                    throw new IllegalStateException("Invalid or excessive source ancestry");
                }
                byte[] bytes = original.get(TBLOCK_HEADER, cursor);
                if (bytes == null) {
                    throw new IllegalStateException("Missing source header");
                }
                BlockHeader header = BlockHeader.deserialize(bytes);
                if (!Arrays.equals(header.calculateHash(), cursor)) {
                    throw new IllegalStateException("Corrupt source header");
                }
                path.write(cursor);
                cursor = header.getPreviousHash();
            }
            try (RocksDb rebuilt = open(config(destination, network))) {
                ChainStateStore state = new ChainStateStore(rebuilt, hash -> block(rebuilt, hash));
                Block head = BlockChainServiceImpl.createGenesisBlock(params);
                state.initialize(head, params);
                for (int index = count - 1; index >= 0; index--) {
                    path.seek((long) index * 32);
                    byte[] hash = new byte[32];
                    path.readFully(hash);
                    byte[] header = original.get(TBLOCK_HEADER, hash), body = original.get(TBLOCK_BODY, hash);
                    if (header == null || body == null) {
                        throw new IllegalStateException("Missing source block");
                    }
                    Block next = Block.buildBlock(BlockHeader.deserialize(header), BlockBody.deserialize(body));
                    if (!state.accept(next, head, params).mainChanged()) {
                        throw new IllegalStateException("Reindex rejected source block");
                    }
                    head = next;
                }
                if (!Arrays.equals(head.getHash(), expectedTip)) {
                    throw new IllegalStateException("Rebuilt tip mismatch");
                }
                byte[] identity = original.get(PEER, SystemConfig.PEER_KEY);
                if (identity != null
                        && !rebuilt.dataTransaction(List.of(new RocksDb.DbOperation(PEER, SystemConfig.PEER_KEY,
                                identity, RocksDb.DbOperation.OpType.INSERT)))) {
                    throw new IllegalStateException("Identity copy failed");
                }
                validateMetadata(rebuilt, params);
            }
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    private static SystemConfig config(Path path, String network) {
        SystemConfig config = new SystemConfig();
        config.setPath(path.toString());
        config.setNetParams(network);
        config.setDbSyncWrites(true);
        config.getParams();
        return config;
    }

    private static RocksDb open(SystemConfig config) {
        RocksDb db = new RocksDb();
        if (!db.createDatabase(config)) {
            db.close();
            throw new IllegalStateException("Cannot open database; stop the node first");
        }
        return db;
    }

    private static Block validateMetadata(RocksDb db, ParamsConfig.Params params) {
        if (!Arrays.equals(db.get(CHAIN, ChainStateStore.REVISION_KEY), RocksDb.intToBytes(ConsensusRules.REVISION))
                || !Arrays.equals(db.get(HEIGHT_TO_HASH, 0),
                        BlockChainServiceImpl.createGenesisBlock(params).getHash())) {
            throw new IllegalStateException(
                    "Incompatible consensus revision or network; old consensus data cannot be silently migrated");
        }
        Block tip = block(db, db.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
        if (tip == null
                || !Arrays.equals(db.get(CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HEIGHT_KEY),
                        RocksDb.intToBytes(tip.getHeight()))
                || !Arrays.equals(db.get(HEIGHT_TO_HASH, tip.getHeight()), tip.getHash())) {
            throw new IllegalStateException("Invalid chain tip");
        }
        UtxoTransition.deserialize(db.get(BLOCK_UNDO, tip.getHash()));
        return tip;
    }

    private static Block block(RocksDb db, byte[] hash) {
        if (hash == null || hash.length != 32) {
            return null;
        }
        byte[] header = db.get(TBLOCK_HEADER, hash), body = db.get(TBLOCK_BODY, hash);
        if (header == null || body == null) {
            return null;
        }
        Block block = Block.buildBlock(BlockHeader.deserialize(header), BlockBody.deserialize(body));
        block.setChainWork(UInt256.fromBytes(db.get(HASH_TO_CHAIN_WORK, hash)));
        return block;
    }

    private static String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = stream.read(buffer)) >= 0) {
                if (n > 0) {
                    digest.update(buffer, 0, n);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void writeManifest(Path directory) throws Exception {
        List<String> lines = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                    throw new IllegalArgumentException("Unexpected checkpoint entry");
                }
                if (!file.getFileName().toString().equals(MANIFEST)) {
                    lines.add(hash(file) + "  " + file.getFileName());
                }
            }
        }
        Files.write(directory.resolve(MANIFEST), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    public static void verifyManifest(Path directory) throws Exception {
        Path manifest = directory.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest) || Files.size(manifest) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Missing or oversized backup manifest");
        }
        Set<String> expected = new HashSet<>(Set.of(MANIFEST));
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.length() < 67 || !line.substring(0, 64).matches("[0-9a-f]{64}")
                    || !line.substring(64, 66).equals("  ")) {
                throw new IllegalArgumentException("Invalid backup manifest");
            }
            String name = line.substring(66);
            if (!name.matches("[a-zA-Z0-9_.-]+") || !expected.add(name)) {
                throw new IllegalArgumentException("Invalid backup filename");
            }
            Path file = directory.resolve(name);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || !hash(file).equals(line.substring(0, 64))) {
                throw new IllegalStateException("Backup checksum mismatch: " + name);
            }
        }
        try (var files = Files.list(directory)) {
            if (!files.map(file -> file.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                    .equals(expected)) {
                throw new IllegalStateException("Unexpected backup files");
            }
        }
    }
}
