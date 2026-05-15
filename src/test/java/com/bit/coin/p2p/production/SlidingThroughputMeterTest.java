package com.bit.coin.p2p.production;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlidingThroughputMeterTest {
    @Test
    void reportsRealSlidingWindowThroughput() {
        AtomicLong now = new AtomicLong(0);
        SlidingThroughputMeter meter = new SlidingThroughputMeter(Duration.ofSeconds(10), now::get);

        meter.recordBytes(1_000);
        now.set(1_000);
        assertEquals(1_000.0, meter.bytesPerSecond(), 0.001);

        now.set(5_000);
        meter.recordBytes(1_000);
        assertEquals(400.0, meter.bytesPerSecond(), 0.001);
        assertEquals(2_000, meter.bytesInWindow());

        now.set(11_000);
        assertEquals(1_000, meter.bytesInWindow());
        assertEquals(166.67, meter.bytesPerSecond(), 0.5);

        now.set(16_000);
        assertEquals(0, meter.bytesInWindow());
        assertEquals(0.0, meter.bytesPerSecond(), 0.001);
    }
}
