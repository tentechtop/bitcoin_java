package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.p2p.protocol.ProtocolRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.bit.coin.p2p.conn.QuicConnectionManager.getConnection;
import static com.bit.coin.p2p.conn.QuicConstants.MSG_RESPONSE_FUTURECACHE;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class QuicDataProcessor {

    @Autowired
    private ProtocolRegistry protocolRegistry;
    @Autowired
    private com.bit.coin.config.SystemConfig systemConfig;

    // 消费线程池
    private ExecutorService consumerExecutor;

    // 线程池核心配置（示例值，根据业务调整）
    private static final int CORE_POOL_SIZE = 6;    // 核心线程数
    private static final int MAX_POOL_SIZE = 6;     // 最大线程数
    private static final int KEEP_ALIVE_SECONDS = 60; // 空闲线程存活时间
    private static final int QUEUE_CAPACITY = 100;  // 线程池任务队列容量
    private static final String THREAD_PREFIX = "quic-consumer-"; // 线程名前缀

    /**
     * 初始化：启动消费线程池
     */
    @PostConstruct
    public void initConsumer() {
        if (!systemConfig.isNetworkingEnabled()) {
            return;
        }
        // 构建线程池（有界队列+拒绝策略，避免OOM/消息丢失）
        consumerExecutor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                // 自定义线程工厂（设置名称+守护线程）
                r -> {
                    Thread thread = new Thread(r, THREAD_PREFIX + System.currentTimeMillis());
                    thread.setDaemon(true); // 应用关闭时自动退出
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者兜底，避免丢消息
        );

        // 提交批量消费任务
        consumerExecutor.submit(this::batchConsume);
        log.info("Quic批量消费线程池初始化完成");
    }

    /** 阻塞等候首条消息，再非阻塞取最多 16 条，一起分发。 */
    private void batchConsume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                QuicMsg firstMsg = QuicConstants.takeMsg();
                log.debug("获取到首条消息，开始批量提取剩余消息");

                List<QuicMsg> remainingMsgs = QuicConstants.drainAllMsg();

                List<QuicMsg> batchMsgs = new ArrayList<>(remainingMsgs.size() + 1);
                batchMsgs.add(firstMsg);
                batchMsgs.addAll(remainingMsgs);

                processBatch(batchMsgs);
                log.debug("本次批量处理完成，共处理{}条消息", batchMsgs.size());

            } catch (InterruptedException e) {
                // 线程被中断，优雅退出循环
                log.debug("消费线程被中断，准备退出");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 非中断异常：记录日志，继续消费（避免单条异常终止整个消费）
                log.error("批量消费过程中发生异常", e);
            }
        }
    }

    /** 按出队顺序分发，单条消息失败时继续处理余下消息。 */
    private void processBatch(List<QuicMsg> batchMsgs) {
        for (int i = 0; i < batchMsgs.size(); i++) {
            QuicMsg msg = batchMsgs.get(i);
            try {
                // 处理单条消息（解析、校验、业务处理等）
                processSingleMsg(msg, i + 1);
            } catch (Exception e) {
                // 单条消息失败不影响整体，仅记录日志
                log.error("处理第{}条消息失败（长度：{}字节）", i + 1, msg.wireLength(), e);
            }
        }
    }

    /** 响应直接交给等待请求的线程。 */
    public static boolean completeResponse(QuicMsg msg) {
        P2PMessage response = msg.message();
        if (!response.isResponse()) {
            return false;
        }
        // 请求 ID 还需同时匹配连接和发送方，避免其他连接上的同 ID 响应完成本地 future。
        QuicConnection connection = getConnection(msg.connectionId());
        String id = bytesToHex(response.getRequestId());
        Long expected = QuicConstants.MSG_EXPECTED_CONNECTIONS.getIfPresent(id);
        if (connection == null || !connection.getPeerId().equals(bytesToHex(response.getSenderId()))
                || expected == null || expected != msg.connectionId()) {
            return true;
        }
        CompletableFuture<P2PMessage> future = MSG_RESPONSE_FUTURECACHE.asMap().remove(id);
        if (future != null) {
            future.complete(response);
        }
        return true;
    }

    void processSingleMsg(QuicMsg msg, int index) {
        try {
            P2PMessage message = msg.message();
            QuicConnection connection = getConnection(msg.connectionId());
            if (!msg.trustedLocal() && (connection == null
                    || !connection.getPeerId().equals(bytesToHex(message.getSenderId()))
                    || message.getType() == ProtocolEnum.Handshake_Success_V1.getCode())) {
                return;
            }
            if (message.isRequest()) {
                ProtocolHandler handler = protocolRegistry.getHandlerMap()
                        .get(ProtocolEnum.fromCode(message.getType()));
                if (handler == null) {
                    log.info("未注册的协议：{}", message.getType());
                    return;
                }
                byte[] reply = handler.handle(message);
                if (reply != null) {
                    QuicConnection active = getConnection(msg.connectionId());
                    if (active != null) {
                        active.sendData(reply);
                    }
                }
            } else if (message.isResponse()) {
                completeResponse(msg);
            } else {
                log.debug("收到普通消息，类型={}，长度={}", message.getType(), msg.wireLength());
            }
        } catch (Exception e) {
            log.error("处理第{}条消息失败（长度：{}字节）", index, msg.wireLength(), e);
        }
    }

    /**
     * 销毁：优雅关闭线程池
     */
    @PreDestroy
    public void destroyConsumer() {
        if (consumerExecutor == null) {
            return;
        }

        log.info("开始关闭Quic消费线程池");
        consumerExecutor.shutdownNow(); // 停止接收新任务
        try {
            // 等待5秒让现有任务执行完毕，超时则强制关闭
            if (!consumerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("线程池关闭超时，强制终止剩余任务");
                consumerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("等待线程池关闭时被中断", e);
            consumerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Quic消费线程池已关闭");
    }

    // ========== 可选：对外提供手动批量提取方法（供监控/测试） ==========
    public List<QuicMsg> manualDrainAll() {
        return QuicConstants.drainAllMsg();
    }
}
