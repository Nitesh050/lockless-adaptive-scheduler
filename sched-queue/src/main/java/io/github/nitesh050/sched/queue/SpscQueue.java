package io.github.nitesh050.sched.queue;

import io.github.nitesh050.sched.common.api.TaskQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * Lock-free bounded single-producer single-consumer ring buffer (the XQueue building block).
 *
 * <p><b>How it stays correct without locks.</b> Only the producer writes {@code tail} and only
 * the consumer writes {@code head}, so neither needs a CAS. What remains is ordering:
 * <ul>
 *   <li>The producer writes the slot, then publishes {@code tail} with a release store. The
 *       consumer reads {@code tail} with an acquire load, then the slot. Release/acquire
 *       guarantees that a consumer seeing the new tail also sees the slot's contents, and
 *       everything the producer wrote into the element before offering it.</li>
 *   <li>The consumer reads (and clears) the slot, then publishes {@code head} with a release
 *       store. The producer reads {@code head} with an acquire load before reusing the slot,
 *       so it never overwrites an element the consumer has not finished reading.</li>
 * </ul>
 * On x86 (TSO) both orderings come for free in hardware, which is what X-OpenMP relies on. The
 * JVM gives no such guarantee (the JIT may reorder plain accesses, and ARM CPUs reorder in
 * hardware), so they are spelled out here. See docs/memory-model.md.
 *
 * <p><b>Speed.</b> Each side keeps a private cached copy of the other side's index and re-reads
 * the shared one only when the cache says the queue looks full (producer) or empty (consumer).
 * The two indices sit on separate cache lines (the padding classes below), so the producer
 * and consumer do not invalidate each other's cache line on every operation.
 *
 * @param <T> element type; null is not allowed
 */
public final class SpscQueue<T> extends SpscTailFields implements TaskQueue<T> {

    private static final VarHandle HEAD;
    private static final VarHandle TAIL;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HEAD = lookup.findVarHandle(SpscHeadFields.class, "head", long.class);
            TAIL = lookup.findVarHandle(SpscTailFields.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Object[] buffer;
    private final int mask;

    // trailing padding so the next object on the heap cannot share the tail's cache line
    long q00, q01, q02, q03, q04, q05, q06, q07, q08, q09, q10, q11, q12, q13, q14, q15;

    /** @param capacity a power of two, at least 2 */
    public SpscQueue(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2, got " + capacity);
        }
        this.buffer = new Object[capacity];
        this.mask = capacity - 1;
    }

    /** Producer thread only. */
    @Override
    public boolean offer(T element) {
        Objects.requireNonNull(element, "element");
        long t = (long) TAIL.get(this); // plain read: only this thread writes tail
        if (t - headCache >= buffer.length) {
            headCache = (long) HEAD.getAcquire(this); // see the consumer's latest progress
            if (t - headCache >= buffer.length) {
                return false;
            }
        }
        buffer[(int) t & mask] = element;    // plain write of the slot...
        TAIL.setRelease(this, t + 1);        // ...made visible by publishing the new tail
        return true;
    }

    /** Consumer thread only. */
    @Override
    @SuppressWarnings("unchecked")
    public T poll() {
        long h = (long) HEAD.get(this); // plain read: only this thread writes head
        if (h >= tailCache) {
            tailCache = (long) TAIL.getAcquire(this); // see the producer's latest slots
            if (h >= tailCache) {
                return null;
            }
        }
        int slot = (int) h & mask;
        T element = (T) buffer[slot];
        buffer[slot] = null;            // drop the reference so the task can be collected
        HEAD.setRelease(this, h + 1);   // hand the slot back to the producer
        return element;
    }

    /** Exact on the producer or consumer thread; approximate (but within [0, capacity]) elsewhere. */
    @Override
    public int size() {
        // head first: tail only grows, so a later tail read can only make the result larger
        long h = (long) HEAD.getAcquire(this);
        long t = (long) TAIL.getAcquire(this);
        long size = t - h;
        return (int) Math.max(0, Math.min(size, buffer.length));
    }

    @Override
    public int capacity() {
        return buffer.length;
    }
}

/*
 * Field layout. The JVM lays out superclass fields before subclass fields, so this chain keeps
 * the consumer's fields and the producer's fields at least 128 bytes apart (one cache line on
 * Apple Silicon, two on x86) without needing -XX:-RestrictContended.
 */

abstract class SpscPad0 {
    long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15;
}

/** Consumer-owned: {@code head} is written only by the consumer. */
abstract class SpscHeadFields extends SpscPad0 {
    long head;
    /** Consumer's private copy of {@code tail}. */
    long tailCache;
}

abstract class SpscPad1 extends SpscHeadFields {
    long p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30, p31, p32, p33, p34, p35;
}

/** Producer-owned: {@code tail} is written only by the producer. */
abstract class SpscTailFields extends SpscPad1 {
    long tail;
    /** Producer's private copy of {@code head}. */
    long headCache;
}
