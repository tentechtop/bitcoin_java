package com.bit.coin.p2p.conn;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 高并发有序容器：FIFO顺序 + O(1)随机删除/存在判断
 * 核心：LinkedBlockingDeque（维护顺序） + ConcurrentHashMap（快速索引）
 */
public class OrderedConcurrentSet {
    // 维护FIFO顺序：队首=最早加入的dataId，队尾=最新加入的dataId
    private final LinkedBlockingDeque<Long> orderDeque;
    // 维护dataId存在性：key=dataId，value=占位（仅标记存在）
    private final ConcurrentHashMap<Long, Boolean> existMap;

    // 构造方法：默认无界，也可指定容量（推荐有界，防止内存溢出）
    public OrderedConcurrentSet() {
        this.orderDeque = new LinkedBlockingDeque<>();
        this.existMap = new ConcurrentHashMap<>();
    }

    // 有界构造：限制最大未ACK数据量（根据服务器性能调整，如10000）
    public OrderedConcurrentSet(int capacity) {
        this.orderDeque = new LinkedBlockingDeque<>(capacity);
        this.existMap = new ConcurrentHashMap<>();
    }

    /**
     * 队尾添加dataId（新增）
     * @return true=添加成功，false=队列满（有界时）
     */
    public boolean offerLast(Long dataId) {
        if (dataId == null) {
            return false;
        }
        // 先加Map（防止重复添加），再加Deque
        if (existMap.putIfAbsent(dataId, Boolean.TRUE) == null) {
            try {
                return orderDeque.offer(dataId);
            } catch (Exception e) {
                // Deque添加失败，回滚Map（保证一致性）
                existMap.remove(dataId);
                return false;
            }
        }
        return true; // 已存在，无需重复添加
    }

    /**
     * 队首取出dataId（优先处理最早数据）
     * @return 队首dataId，null=空
     */
    public Long pollFirst() {
        // 循环取数：过滤Deque中已被删除（Map中不存在）的无效dataId
        while (true) {
            Long dataId = orderDeque.poll();
            if (dataId == null) {
                return null;
            }
            // 若Map中存在，说明是有效数据；否则跳过（已被ACK删除）
            if (existMap.containsKey(dataId)) {
                return dataId;
            }
        }
    }

    /**
     * 随机删除指定dataId（ACK后核销）
     * @return true=删除成功，false=不存在
     */
    public boolean remove(Long dataId) {
        if (dataId == null) {
            return false;
        }
        // 先删Map（O(1)），Deque中的无效数据靠pollFirst时过滤（无需主动删）
        return existMap.remove(dataId) != null;
    }

    /**
     * 判断dataId是否存在
     * @return true=存在（未ACK）
     */
    public boolean contains(Long dataId) {
        return dataId != null && existMap.containsKey(dataId);
    }

    /**
     * 是否为空
     */
    public boolean isEmpty() {
        return existMap.isEmpty();
    }

    /**
     * 获取未ACK数据量
     */
    public int size() {
        return existMap.size();
    }

    /**
     * 清空所有数据
     */
    public void clear() {
        existMap.clear();
        orderDeque.clear();
    }



    /**
     * 队首添加dataId（优先处理该数据）
     * @param dataId 待添加的dataId
     * @return true=添加成功（或已存在），false=队列满（有界时）/dataId为空
     */
    public boolean offerFirst(Long dataId) {
        // 1. 空值校验：dataId为空直接返回失败
        if (dataId == null) {
            return false;
        }

        // 2. 先通过Map防止重复添加：putIfAbsent原子操作，保证线程安全
        //    返回null → 该dataId不存在，需要添加到Deque；返回非null → 已存在，直接返回成功
        if (existMap.putIfAbsent(dataId, Boolean.TRUE) == null) {
            try {
                // 3. 尝试添加到Deque队首（非阻塞，队列满则返回false）
                boolean offerSuccess = orderDeque.offerFirst(dataId);
                if (!offerSuccess) {
                    // 4. Deque添加失败（如队列满），回滚Map → 保证Map和Deque数据一致
                    existMap.remove(dataId);
                    return false;
                }
                return true; // 添加成功
            } catch (Exception e) {
                // 5. 异常兜底：任何异常都回滚Map，避免内存泄漏
                existMap.remove(dataId);
                // 打印异常日志（建议接入项目日志框架）
                System.err.printf("[OrderedConcurrentSet] offerFirst失败，dataId=%d，异常：%s%n", dataId, e.getMessage());
                return false;
            }
        }

        // 3. dataId已存在：无需重复添加，返回成功（与offerLast语义对齐）
        return true;
    }


    /**
     * 获取有序迭代器（弱一致性）
     * 迭代顺序：队首（最早加入）→ 队尾（最新加入）
     * 特性：
     * 1. 自动过滤existMap中已删除的无效dataId；
     * 2. 支持并发增删，不抛ConcurrentModificationException；
     * 3. 迭代器的hasNext()/next()是原子的，但整体遍历不保证快照一致性；
     * 4. 不支持remove()操作（如需删除请调用外层Set的remove()）。
     * @return 按FIFO顺序的dataId迭代器
     */
    public Iterator<Long> iterator() {
        // 1. 先获取Deque的弱一致性迭代器（LinkedBlockingDeque的iterator()本身是弱一致性的）
        Iterator<Long> dequeIterator = orderDeque.iterator();

        // 2. 包装迭代器，过滤无效数据（Map中已删除的dataId）
        return new Iterator<Long>() {
            // 缓存下一个有效的dataId（解决hasNext和next的原子性）
            private Long nextValidDataId = null;

            /**
             * 检查是否有下一个有效数据
             * 逻辑：遍历Deque，直到找到existMap中存在的dataId，或遍历结束
             */
            @Override
            public boolean hasNext() {
                // 已缓存有效数据，直接返回true
                if (nextValidDataId != null) {
                    return true;
                }

                // 遍历Deque，过滤无效数据
                while (dequeIterator.hasNext()) {
                    Long currentDataId = dequeIterator.next();
                    // 检查dataId是否有效（存在于existMap中）
                    if (existMap.containsKey(currentDataId)) {
                        nextValidDataId = currentDataId;
                        return true;
                    }
                    // 无效数据：跳过（Deque中的脏数据，靠pollFirst/迭代时自动清理）
                }

                // 无有效数据
                return false;
            }

            /**
             * 获取下一个有效数据
             * 前置：必须先调用hasNext()，否则可能返回null
             */
            @Override
            public Long next() {
                // 先触发hasNext()，保证nextValidDataId有值
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException("没有更多有效数据");
                }

                // 取出缓存的有效数据，并清空缓存
                Long result = nextValidDataId;
                nextValidDataId = null;
                return result;
            }

            /**
             * 不支持移除操作（外层Set的remove()才是原子安全的）
             * 原因：迭代器的remove()无法保证Deque和Map的一致性，强制调用抛异常
             */
            @Override
            public void remove() {
                throw new UnsupportedOperationException("迭代器不支持remove()，请调用OrderedConcurrentSet.remove(Long)");
            }
        };
    }
}