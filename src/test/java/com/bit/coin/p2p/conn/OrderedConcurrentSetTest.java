package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderedConcurrentSetTest {
    @Test
    void boundedOfferRollsBackWhenFull() {
        OrderedConcurrentSet set = new OrderedConcurrentSet(1);

        assertTrue(set.offerLast(1L));
        assertFalse(set.offerLast(2L));

        assertTrue(set.contains(1L));
        assertFalse(set.contains(2L));
        assertEquals(1, set.size());
    }

    @Test
    void offerFirstMovesExistingDataIdToHead() {
        OrderedConcurrentSet set = new OrderedConcurrentSet();
        set.offerLast(1L);
        set.offerLast(2L);
        set.offerLast(3L);

        assertTrue(set.offerFirst(3L));

        assertEquals(3L, set.pollFirst());
        assertEquals(1L, set.pollFirst());
        assertEquals(2L, set.pollFirst());
    }

    @Test
    void iteratorUsesStableSnapshot() {
        OrderedConcurrentSet set = new OrderedConcurrentSet();
        set.offerLast(1L);
        set.offerLast(2L);

        Iterator<Long> iterator = set.iterator();
        set.remove(1L);

        assertEquals(1L, iterator.next());
        assertEquals(2L, iterator.next());
        assertEquals(1, set.size());
    }
}
