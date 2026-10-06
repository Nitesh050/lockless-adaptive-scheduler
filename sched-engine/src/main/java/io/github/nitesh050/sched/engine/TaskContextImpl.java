package io.github.nitesh050.sched.engine;

import io.github.nitesh050.sched.common.api.DeadlockException;
import io.github.nitesh050.sched.common.api.ResourceManager;
import io.github.nitesh050.sched.common.api.TaskContext;
import io.github.nitesh050.sched.common.model.Task;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import io.github.nitesh050.sched.common.model.TaskState;

/** The context for one execution of one task. Created per task, used only on its worker. */
final class TaskContextImpl implements TaskContext {

    private final Worker worker;
    private final TaskControlBlock tcb;
    private final ResourceManager resources;
    private boolean usedResources;

    TaskContextImpl(Worker worker, TaskControlBlock tcb, ResourceManager resources) {
        this.worker = worker;
        this.tcb = tcb;
        this.resources = resources;
    }

    @Override
    public long taskId() {
        return tcb.id();
    }

    @Override
    public int workerIndex() {
        return worker.id();
    }

    @Override
    public void spawn(Task task) {
        worker.spawn(tcb.id(), task);
    }

    @Override
    public void acquire(String resource) throws DeadlockException, InterruptedException {
        ResourceManager rm = requireResources();
        usedResources = true;
        tcb.transition(TaskState.RUNNING, TaskState.BLOCKED);
        try {
            rm.acquire(tcb.id(), resource);
        } finally {
            tcb.transition(TaskState.BLOCKED, TaskState.RUNNING);
        }
    }

    @Override
    public boolean tryAcquire(String resource) {
        ResourceManager rm = requireResources();
        usedResources = true;
        return rm.tryAcquire(tcb.id(), resource);
    }

    @Override
    public void release(String resource) {
        requireResources().release(tcb.id(), resource);
    }

    /** Releases anything the task still holds. Called by the worker when the task ends. */
    void close() {
        if (usedResources) {
            resources.releaseAll(tcb.id());
        }
    }

    private ResourceManager requireResources() {
        if (resources == null) {
            throw new UnsupportedOperationException("no ResourceManager configured for this run");
        }
        return resources;
    }
}
