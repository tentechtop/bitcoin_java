package com.bit.coin.p2p.conn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 功能目的：维护未确认 dataId 的有序集合；实现原因：队列与索引必须原子更新，避免重传优先级和存在性状态不一致。
 */
public class OrderedConcurrentSet {
    private static final int UNBOUNDED_CAPACITY = Integer.MAX_VALUE;

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Long> orderDeque;
    private final Set<Long> existSet;
    private final int capacity;

    public OrderedConcurrentSet() {
        this(UNBOUNDED_CAPACITY);
    }

    public OrderedConcurrentSet(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.orderDeque = new ArrayDeque<>(Math.min(capacity, 1024));
        this.existSet = new HashSet<>();
    }

    public boolean offerLast(Long dataId) {
        if (dataId == null) {
            return false;
        }
        lock.lock();
        try {
            if (existSet.contains(dataId)) {
                return true;
            }
            if (existSet.size() >= capacity) {
                return false;
            }
            orderDeque.offerLast(dataId);
            existSet.add(dataId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean offerFirst(Long dataId) {
        if (dataId == null) {
            return false;
        }
        lock.lock();
        try {
            if (existSet.contains(dataId)) {
                orderDeque.remove(dataId);
                orderDeque.offerFirst(dataId);
                return true;
            }
            if (existSet.size() >= capacity) {
                return false;
            }
            orderDeque.offerFirst(dataId);
            existSet.add(dataId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public Long pollFirst() {
        lock.lock();
        try {
            Long dataId = orderDeque.pollFirst();
            if (dataId == null) {
                return null;
            }
            existSet.remove(dataId);
            return dataId;
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(Long dataId) {
        if (dataId == null) {
            return false;
        }
        lock.lock();
        try {
            if (!existSet.remove(dataId)) {
                return false;
            }
            orderDeque.remove(dataId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean contains(Long dataId) {
        if (dataId == null) {
            return false;
        }
        lock.lock();
        try {
            return existSet.contains(dataId);
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return existSet.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return existSet.size();
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            orderDeque.clear();
            existSet.clear();
        } finally {
            lock.unlock();
        }
    }

    public Iterator<Long> iterator() {
        List<Long> snapshot;
        lock.lock();
        try {
            snapshot = new ArrayList<>(orderDeque);
        } finally {
            lock.unlock();
        }
        Iterator<Long> iterator = snapshot.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public Long next() {
                if (!iterator.hasNext()) {
                    throw new NoSuchElementException("no more dataId");
                }
                return iterator.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("call OrderedConcurrentSet.remove(Long)");
            }
        };
    }
}
