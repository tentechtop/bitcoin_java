package com.bit.coin.database.rocksDb;

import com.bit.coin.config.SystemConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
