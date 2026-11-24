package com.bit.coin.database.rocksDb;

import com.bit.coin.config.SystemConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbConcurrencyTest {

    private static final TableEnum TABLE = TableEnum.TBLOCK_BODY;
    @TempDir
    Path directory;
    private RocksDb database;
    private ExecutorService executor;

    @BeforeEach
    void openDatabase() {
        database = new RocksDb();
        SystemConfig config = new SystemConfig();
        config.setPath(directory.resolve("rocksdb").toString());
        assertTrue(database.createDatabase(config));
        executor = Executors.newFixedThreadPool(3);
        database.insert(TABLE, new byte[] { 1 }, new byte[] { 1 });
    }

    @AfterEach
    void closeDatabase() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void writeCanCompleteWhileIterationCallbackIsRunning() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Future<?> iteration = executor.submit(() -> database.iterate(TABLE, (key, value) -> {
            callbackEntered.countDown();
            await(releaseCallback);
            return false;
        }));

        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
        try {
            Future<?> write = executor.submit(() -> database.insert(TABLE, new byte[] { 2 }, new byte[] { 2 }));
            write.get(1, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 2 }, database.get(TABLE, new byte[] { 2 }));
        } finally {
            releaseCallback.countDown();
        }
        iteration.get(5, TimeUnit.SECONDS);
    }

    @Test
    void closeWaitsForRunningIteration() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        Future<?> iteration = executor.submit(() -> database.iterate(TABLE, (key, value) -> {
            callbackEntered.countDown();
            await(releaseCallback);
            return false;
        }));
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

        Future<?> close = executor.submit(() -> {
            closeStarted.countDown();
            database.close();
        });
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
        try {
            assertFalse(close.isDone());
        } finally {
            releaseCallback.countDown();
        }

        iteration.get(5, TimeUnit.SECONDS);
        close.get(5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> database.get(TABLE, new byte[] { 1 }));
    }

    @Test
    void cacheIsCoherentAcrossReadsBatchesAndTransactions() {
        byte[] key = { 3 };
        byte[] original = { 7, 8 };
        database.insert(TABLE, key, original);

        byte[] firstRead = database.get(TABLE, key);
        firstRead[0] = 99;
        assertArrayEquals(original, database.get(TABLE, key), "cached values must be defensive copies");

        database.update(TABLE, key, new byte[] { 9 });
        assertArrayEquals(new byte[] { 9 }, database.get(TABLE, key));

        byte[] batchKey = { 4 };
        database.batchInsert(TABLE, new byte[][] { batchKey }, new byte[][] { { 10 } });
        assertArrayEquals(new byte[] { 10 }, database.get(TABLE, batchKey));

        byte[] transactionKey = { 5 };
        assertTrue(database.dataTransaction(List.of(
                new RocksDb.DbOperation(TABLE, transactionKey, new byte[] { 11 }, RocksDb.DbOperation.OpType.INSERT))));
        assertArrayEquals(new byte[] { 11 }, database.get(TABLE, transactionKey));

        database.batchDelete(TABLE, new byte[][] { batchKey });
        assertNull(database.get(TABLE, batchKey));
        database.delete(TABLE, key);
        assertNull(database.get(TABLE, key));
    }

    @Test
    void cachePolicyAndRefreshValidateArguments() {
        assertThrows(IllegalArgumentException.class, () -> database.setCachePolicy(TABLE, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> database.setCachePolicy(TABLE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> database.refreshCache(null, null));

        database.setCachePolicy(TABLE, 0, 2);
        byte[] firstKey = { 6 };
        byte[] secondKey = { 7 };
        database.insert(TABLE, firstKey, new byte[] { 12 });
        database.insert(TABLE, secondKey, new byte[] { 13 });

        // Seed stale values directly in the cache so a one-entry refresh cannot
        // pass this test by falling back to RocksDB on the second read.
        database.getTableCaches().get(TABLE).put(bytesToHex(firstKey), new byte[] { 99 });
        database.getTableCaches().get(TABLE).put(bytesToHex(secondKey), new byte[] { 98 });
        database.refreshCache(TABLE, null);
        assertArrayEquals(new byte[] { 12 }, database.get(TABLE, firstKey));
        assertArrayEquals(new byte[] { 13 }, database.get(TABLE, secondKey));
        database.clearCache(TABLE);
        assertArrayEquals(new byte[] { 12 }, database.get(TABLE, firstKey));

        database.insert(TABLE, new byte[] { 7 }, new byte[] { 13 });
        assertArrayEquals(new byte[] { 13 }, database.get(TABLE, new byte[] { 7 }));
        database.batchDeleteRange(TABLE, new byte[] { 7 }, new byte[] { 8 });
        assertNull(database.get(TABLE, new byte[] { 7 }));
    }

    @Test
    void aliasesForOneColumnFamilyShareCacheState() {
        byte[] key = { 20 };
        database.insert(TableEnum.TEST1, key, new byte[] { 21 });
        assertArrayEquals(new byte[] { 21 }, database.get(TableEnum.TEST2, key));

        database.update(TableEnum.TEST2, key, new byte[] { 22 });
        assertArrayEquals(new byte[] { 22 }, database.get(TableEnum.TEST1, key));
        database.clearCache(TableEnum.TEST2);
        assertArrayEquals(new byte[] { 22 }, database.get(TableEnum.HEIGHT_TO_HASH, key));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for callback release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
