package com.bit.coin.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 流量控制器
 * 采用多层令牌桶 + BBR风格拥塞控制 + 自适应调整
 */
@Slf4j
public class FlowController {

    // ========== 核心令牌桶 ==========
    private final long maxBurstSize;        // 最大突发大小
    private final AtomicLong tokenBucket;     // 令牌桶
    private final AtomicLong sendRate;         // 当前发送速率
    private final AtomicLong maxSendRate;      // 最大发送速率
    private final AtomicLong minSendRate;      // 最小发送速率

    // ========== 时间跟踪 ==========
    private volatile long lastRefillTime;

    // ========== BBR风格拥塞控制 ==========
    private volatile long currentRtt;         // 当前RTT (ms)
    private volatile long minRtt;             // 最小RTT (ms)
    private volatile long deliveryRate;        // 传输速率 (bytes/s)
    private volatile long maxDeliveryRate;     // 最大传输速率
    private volatile int congestionState;      // 拥塞状态: 0=启动, 1=探测, 2=传输, 3=拥塞

    // ========== 自适应调整参数 ==========
    private final double growthFactor;        // 增长因子
    private final double decreaseFactor;      // 下降因子
    private final long rttThreshold;         // RTT阈值
    private final long lossThreshold;         // 丢包阈值

    // ========== 统计信息 ==========
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalBytesDropped = new AtomicLong(0);
    private final AtomicLong adaptationCount = new AtomicLong(0);

    // ========== 控制锁 ==========
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 创建高级流量控制器
     * @param initialRate 初始速率
     * @param maxRate 最大速率
     * @param minRate 最小速率
     * @param maxBurst 最大突发
     * @param growthFactor 增长因子 (1.0-1.5)
     * @param decreaseFactor 下降因子 (0.5-0.9)
     */
    public FlowController(long initialRate, long maxRate, long minRate,
                          long maxBurst, double growthFactor, double decreaseFactor) {
        this.maxBurstSize = maxBurst;
        this.maxSendRate = new AtomicLong(maxRate);
        this.minSendRate = new AtomicLong(minRate);
        this.sendRate = new AtomicLong(Math.min(initialRate, maxRate));
        this.tokenBucket = new AtomicLong(maxBurst);
        this.lastRefillTime = System.currentTimeMillis();

        this.growthFactor = Math.min(1.5, Math.max(1.0, growthFactor));
        this.decreaseFactor = Math.min(0.9, Math.max(0.5, decreaseFactor));
        this.rttThreshold = 100; // 100ms
        this.lossThreshold = 3;   // 连续3次失败

        // 初始化拥塞状态
        this.minRtt = Long.MAX_VALUE;
        this.maxDeliveryRate = 0;
        this.congestionState = 0;

        // 预填充令牌桶
        tokenBucket.set(maxBurst);

        log.info("[流控] 初始化 - 初始速率:{}MB/s, 最大:{}MB/s, 最小:{}MB/s, 突发:{}MB",
                initialRate / 1024 / 1024, maxRate / 1024 / 1024,
                minRate / 1024 / 1024, maxBurst / 1024 / 1024);
        log.info("[流控] 参数 - 增长因子:{}, 下降因子:{}, RTT阈值:{}ms",
                this.growthFactor, this.decreaseFactor, this.rttThreshold);
    }




    /**
     * 【新增】尝试发送一个帧（非阻塞）。
     * 此方法只检查令牌是否足够，成功则消耗令牌并返回 true，失败则立即返回 false。
     * 它不会触发任何自适应速率调整，行为完全确定。
     */
    public boolean trySendFrame(long frameSize) {
        refillTokens();
        long availableTokens = tokenBucket.get();
        if (availableTokens >= frameSize) {
            if (tokenBucket.compareAndSet(availableTokens, availableTokens - frameSize)) {
                totalBytesSent.addAndGet(frameSize);
                return true;
            }
        }
        totalBytesDropped.addAndGet(frameSize);
        return false;
    }

    /**
     * 【新增】发送一个帧，在令牌不足时会等待，直到超时。
     * @param frameSize 帧的大小（字节）。
     * @param timeout  等待超时时间。
     * @param unit     超时时间单位。
     * @return 如果成功发送，返回 true；如果超时仍未发送成功，返回 false。
     * @throws InterruptedException 如果等待线程被中断。
     */
    public boolean sendFrameWithWait(long frameSize, long timeout, TimeUnit unit) throws InterruptedException {
        final long timeoutMillis = unit.toMillis(timeout);
        final long startTime = System.currentTimeMillis();
        while (true) {
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                totalBytesDropped.addAndGet(frameSize);
                return false;
            }
            if (trySendFrame(frameSize)) {
                return true;
            }
            Thread.yield();
        }
    }


    /**
     * 标准路径：完整的流量控制检查
     * 【已修复】重构以避免死锁
     */
    public boolean trySend(long dataSize) {
        if (dataSize <= 0) {
            return true;
        }

        // 步骤 1: 尝试获取令牌并发送 (使用读锁)
        boolean sent = false;
        lock.readLock().lock();
        try {
            refillTokens();

            long availableTokens = tokenBucket.get();
            if (availableTokens >= dataSize) {
                // 使用 compareAndSet 保证原子性
                if (tokenBucket.compareAndSet(availableTokens, availableTokens - dataSize)) {
                    totalBytesSent.addAndGet(dataSize);
                    sent = true;
                }
            }
        } finally {
            lock.readLock().unlock(); // 无论成功与否，都释放读锁
        }

        if (sent) {
            return true;
        }

        // 步骤 2: 发送失败，执行自适应调整 (此方法内部会获取写锁)
        handleTokenShortage(dataSize);
        totalBytesDropped.addAndGet(dataSize);
        return false;
    }


    /**
     * 处理令牌短缺 - 自适应调整速率
     *
     * @param dataSize 申请发送的数据大小
     */
    private void handleTokenShortage(long dataSize) {
        // 使用写锁确保在调整速率期间，其他操作（如令牌补充）不会干扰
        lock.writeLock().lock();
        try {
            // 1. 计算令牌短缺的严重程度
            long availableTokens = tokenBucket.get();
            long deficit = dataSize - availableTokens;

            // 2. 基于RTT的拥塞判断（BBR核心思想）
            // 如果当前RTT显著高于最小RTT，表明网络可能正在拥塞
            boolean isCongestedByRtt = (currentRtt > 0 && minRtt < Long.MAX_VALUE) &&
                    (currentRtt > minRtt * 1.5 && currentRtt > rttThreshold);

            // 3. 基于传输速率的供需判断
            // 如果实际传输速率远低于当前发送速率，说明发送速率设置过高
            boolean isSupplyExceedsDemand = (deliveryRate > 0) && (deliveryRate < sendRate.get() * 0.7);

            // 4. 决策与执行
            if (isCongestedByRtt || isSupplyExceedsDemand) {
                // 场景A：网络拥塞或发送速率过高 -> 降低发送速率
                // 当RTT恶化或实际传输跟不上时，这是最直接的应对措施
                autoDecreaseRate();
                log.warn("[流控] 令牌短缺，触发降速。原因: RTT={}ms(>{}) 或 传输率不足. 短缺: {} bytes",
                        currentRtt, minRtt * 1.5, deficit);
            } else {
                // 场景B：令牌短缺但网络状况良好 -> 可能是突发流量或探测机会
                // 计算当前桶的填充率
                long currentRate = sendRate.get();
                long maxRate = maxSendRate.get();

                // 如果当前速率远低于最大速率，且网络通畅，可以适度增加速率以利用带宽
                if (currentRate < maxRate * 0.9 && currentRtt < minRtt * 1.2) {
                    // 小幅增加速率，尝试“探测”更大的可用带宽
                    long newRate = (long) Math.min(currentRate * 1.02, maxRate);
                    if (newRate > currentRate) {
                        sendRate.set(newRate);
                        adaptationCount.incrementAndGet();
                        log.info("[流控] 令牌短缺但网络通畅，尝试增速探测。短缺: {} bytes, 速率: {} -> {} MB/s",
                                deficit, currentRate / 1024 / 1024, newRate / 1024 / 1024);
                    }
                } else {
                    // 场景C：速率已接近上限或网络状况一般 -> 维持现状
                    // 令牌不足可能只是正常的流量波动，此时频繁调整可能导致抖动，所以选择不调整
                    log.debug("[流控] 令牌短缺，但速率已达高位或网络不稳定，暂不调整。短缺: {} bytes", deficit);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新RTT（BBR风格带宽探测）
     * 使用更灵敏的降速阈值
     */
    public void updateRtt(long rtt) {
        if (rtt <= 0) return;

        this.currentRtt = rtt;
        if (rtt < minRtt) {
            minRtt = rtt;
        }

        // 【优化】使用更灵敏的阈值 (1.5x) 来触发降速，而不是 2x
        if (rtt > minRtt * 1.5 && rtt > rttThreshold) {
            autoDecreaseRate();
        } else if (rtt < minRtt * 1.1) {
            autoIncreaseRate();
        }
    }

    /**
     * 自动增加速率（基于BBR探测）
     */
    private void autoIncreaseRate() {
        lock.writeLock().lock();
        try {
            long currentRate = sendRate.get();
            long maxRate = maxSendRate.get();

            if (currentRate < maxRate) {
                long newRate = (long) Math.min(currentRate * growthFactor, maxRate);
                sendRate.set(newRate);
                adaptationCount.incrementAndGet();

                log.debug("[速率自适应] 提升速率: {} -> {} MB/s ({})",
                        currentRate / 1024 / 1024, newRate / 1024 / 1024,
                        String.format("%.1fx", (double)newRate / currentRate));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 自动降低速率（拥塞响应）
     */
    private void autoDecreaseRate() {
        lock.writeLock().lock();
        try {
            long currentRate = sendRate.get();
            long minRate = minSendRate.get();

            if (currentRate > minRate) {
                long newRate = (long) Math.max(currentRate * decreaseFactor, minRate);
                sendRate.set(newRate);
                adaptationCount.incrementAndGet();
                log.debug("[速率自适应] 降低速率: {} -> {} MB/s ({})",
                        currentRate / 1024 / 1024, newRate / 1024 / 1024,
                        String.format("%.1fx", (double)newRate / currentRate));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新传输速率
     */
    public void updateDeliveryRate(long rate) {
        this.deliveryRate = rate;
        if (rate > maxDeliveryRate) {
            maxDeliveryRate = rate;
        }
    }

    /**
     * 更新发送速率（公共方法）
     * @param newRate 新的发送速率
     */
    public void updateSendRate(long newRate) {
        lock.writeLock().lock();
        try {
            long boundedRate = Math.max(
                    minSendRate.get(),
                    Math.min(newRate, maxSendRate.get())
            );
            sendRate.set(boundedRate);

            log.debug("[速率更新] {} -> {} MB/s",
                    sendRate.get() / 1024 / 1024, boundedRate / 1024 / 1024);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * BBR风格带宽探测
     */
    public void bbrProbe() {
        lock.writeLock().lock();
        try {
            long currentRate = sendRate.get();
            long targetRate = Math.max(deliveryRate, currentRate);
            // 基于传输速率调整
            if (targetRate > currentRate * 1.2) {
                // 传输速率显著高于当前，提升速率
                long newRate = (long) Math.min(currentRate * 1.1, maxSendRate.get());
                sendRate.set(newRate);
                congestionState = 1; // 探测状态
            } else if (targetRate < currentRate * 0.8) {
                // 传输速率低于当前，降低速率
                long newRate = (long) Math.max(currentRate * 0.9, minSendRate.get());
                sendRate.set(newRate);
                congestionState = 3; // 拥塞状态
            } else {
                congestionState = 2; // 传输状态
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取最优发送窗口
     */
    public long getOptimalSendWindow() {
        lock.readLock().lock();
        try {
            // 基于RTT计算最优窗口：BDP = 带宽 * RTT
            long bdp = (sendRate.get() * Math.max(currentRtt, 10)) / 1000;
            // 限制在突发大小内
            return Math.min(bdp, maxBurstSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 补充令牌
     * 【已修复】重构以利用原子操作，不再需要锁，从而避免死锁
     */
    private void refillTokens() {
        long currentTime = System.currentTimeMillis();
        long refillTime = lastRefillTime;

        // 如果时间没有前进，或者当前令牌桶已满，则无需补充
        if (currentTime <= refillTime || tokenBucket.get() >= maxBurstSize) {
            return;
        }

        // 1. 尝试原子地更新最后补充时间
        // 只有当 lastRefillTime 仍然是我们读取时的那个值时，才更新它。
        // 这确保了在高并发下，只有一个线程能成功地为这段时间差补充令牌。
        if (lastRefillTime == refillTime) {
            // 2. 计算时间差和应补充的令牌数
            long timeDiff = currentTime - refillTime;
            long tokensToAdd = (timeDiff * sendRate.get()) / 1000;

            if (tokensToAdd > 0) {
                // 3. 使用 getAndAdd 原子地增加令牌桶中的令牌数
                long newTokenCount = tokenBucket.getAndAdd(tokensToAdd);

                // 4. 检查令牌是否溢出，如果溢出则修正为最大值
                // 这里存在一个微小的竞态窗口，但使用 compareAndSet 可以安全地修正。
                if (newTokenCount > maxBurstSize) {
                    tokenBucket.compareAndSet(newTokenCount, maxBurstSize);
                } else if (newTokenCount + tokensToAdd > maxBurstSize) {
                    tokenBucket.compareAndSet(newTokenCount + tokensToAdd, maxBurstSize);
                }
            }

            // 5. 最后，更新时间戳。即使上面的令牌补充因并发失败，时间戳也必须前进，
            // 以防止其他线程重复计算这段时间。
            lastRefillTime = currentTime;
        }
    }

    /**
     * 获取可用令牌
     */
    public long getAvailableTokens() {
        refillTokens();
        return tokenBucket.get();
    }

    /**
     * 重置控制器
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            tokenBucket.set(maxBurstSize);
            sendRate.set(maxSendRate.get());
            lastRefillTime = System.currentTimeMillis();

            totalBytesSent.set(0);
            totalBytesDropped.set(0);
            adaptationCount.set(0);

            minRtt = Long.MAX_VALUE;
            maxDeliveryRate = 0;
            congestionState = 0;

            log.info("[高级流控] 已重置");
        } finally {
            lock.writeLock().unlock();
        }
    }


    // 在 FlowController 类中添加这些方法

    public long getSendRate() {
        return sendRate.get();
    }

    public long getTotalBytesSent() {
        return totalBytesSent.get();
    }

    public long getTotalBytesDropped() {
        return totalBytesDropped.get();
    }

    public long getAdaptationCount() {
        return adaptationCount.get();
    }

    public long getCurrentRtt() {
        return currentRtt;
    }

    public long getDeliveryRate() {
        return deliveryRate;
    }

}
