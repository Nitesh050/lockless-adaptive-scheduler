package io.github.nitesh050.sched.strategies;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The steal-request handshake: one 64-bit word per worker (the victim), packing
 *
 * <pre>
 *   [ round : 32 bits | thief + 1 : 32 bits ]      thief + 1 == 0  means "no request"
 * </pre>
 *
 * A thief posts a request by CASing its id into an empty cell. The victim answers it by
 * CASing the cell to the next round with no thief, after moving tasks into the thief's queue.
 * The round number lets a thief tell "my request was answered" apart from "the cell is
 * empty again", and stops an old answer being mistaken for a new one.
 *
 * <p>Every transition is a CAS on one word, so no lock is needed, and a lost race only means
 * a failed steal attempt, never a lost task: tasks move from queue to queue, never through
 * the cell. One cell per worker, each on its own cache line.
 *
 * <p>Shared by both stealing strategies, so requests survive a switch between them.
 */
public final class StealRequestCell {

    /** Thief id stored in an empty cell. */
    public static final int NO_THIEF = -1;

    /** 16 longs = 128 bytes: one cell per Apple Silicon cache line. */
    private static final int STRIDE = 16;
    private static final long LOW_32 = 0xFFFF_FFFFL;

    private final AtomicLongArray cells;
    private final int workers;

    public StealRequestCell(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1");
        }
        this.workers = workers;
        this.cells = new AtomicLongArray((workers + 1) * STRIDE);
    }

    public int workers() {
        return workers;
    }

    static long pack(long round, int thief) {
        return ((round & LOW_32) << 32) | ((thief + 1L) & LOW_32);
    }

    public static int thiefOf(long word) {
        return (int) (word & LOW_32) - 1;
    }

    static long roundOf(long word) {
        return word >>> 32;
    }

    private static int index(int victim) {
        return (victim + 1) * STRIDE;
    }

    private long read(int victim) {
        return cells.getAcquire(index(victim));
    }

    // ---- thief side ----------------------------------------------------------------------

    /**
     * Posts a steal request from {@code thief} to {@code victim}.
     *
     * @return the request word to pass to {@link #isAnswered} / {@link #withdraw}, or 0 if
     *     the victim already has a pending request (from another thief)
     */
    public long post(int victim, int thief) {
        long current = read(victim);
        if (thiefOf(current) != NO_THIEF) {
            return 0;
        }
        long request = pack(roundOf(current), thief);
        return cells.compareAndSet(index(victim), current, request) ? request : 0;
    }

    /**
     * True once the victim has answered (or declined) {@code request}. Acquire read: any tasks
     * the victim pushed before answering are visible to the thief afterwards.
     */
    public boolean isAnswered(int victim, long request) {
        return read(victim) != request;
    }

    /**
     * Takes back an unanswered request.
     *
     * @return true if it was withdrawn; false if the victim answered first (the thief should
     *     then look in its own queues for the stolen tasks)
     */
    public boolean withdraw(int victim, long request) {
        return cells.compareAndSet(index(victim), request, pack(roundOf(request), NO_THIEF));
    }

    // ---- victim side ---------------------------------------------------------------------

    /** The pending request word for {@code victim}, or 0 if there is none. */
    public long pending(int victim) {
        long word = read(victim);
        return thiefOf(word) == NO_THIEF ? 0 : word;
    }

    /**
     * Marks {@code request} answered. Call after pushing any stolen tasks, so the release here
     * publishes them before the thief sees the answer.
     *
     * @return false if the thief withdrew in the meantime (tasks already pushed are still in
     *     its queue and will be found there)
     */
    public boolean answer(int victim, long request) {
        return cells.compareAndSet(index(victim), request, pack(roundOf(request) + 1, NO_THIEF));
    }
}
