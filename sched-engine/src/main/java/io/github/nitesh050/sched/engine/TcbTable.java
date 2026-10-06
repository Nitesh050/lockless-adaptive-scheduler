package io.github.nitesh050.sched.engine;

import io.github.nitesh050.sched.common.model.Task;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import io.github.nitesh050.sched.common.model.TaskState;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Creates task control blocks and, when tracking is on, keeps every one of them so a run can
 * be audited (exactly-once checks, snapshot dumps). Tracking costs one concurrent map insert
 * per task, so benchmarks turn it off.
 */
public final class TcbTable {

    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, TaskControlBlock> tracked;

    public TcbTable(boolean track) {
        this.tracked = track ? new ConcurrentHashMap<>() : null;
    }

    public TaskControlBlock create(long parentId, Task task, int spawnedBy) {
        TaskControlBlock tcb = new TaskControlBlock(nextId.getAndIncrement(), parentId, task, spawnedBy);
        if (tracked != null) {
            tracked.put(tcb.id(), tcb);
        }
        return tcb;
    }

    /** Number of TCBs created so far. */
    public long created() {
        return nextId.get();
    }

    public boolean isTracking() {
        return tracked != null;
    }

    /** @return the TCB, or null if unknown or tracking is off */
    public TaskControlBlock get(long id) {
        return tracked == null ? null : tracked.get(id);
    }

    /** Live view of every tracked TCB; empty when tracking is off. */
    public Collection<TaskControlBlock> all() {
        return tracked == null ? Collections.emptyList() : Collections.unmodifiableCollection(tracked.values());
    }

    /** How many tracked tasks are in each state right now (approximate while running). */
    public Map<TaskState, Long> countByState() {
        Map<TaskState, Long> counts = new EnumMap<>(TaskState.class);
        for (TaskState s : TaskState.values()) {
            counts.put(s, 0L);
        }
        for (TaskControlBlock tcb : all()) {
            counts.merge(tcb.state(), 1L, Long::sum);
        }
        return counts;
    }
}
