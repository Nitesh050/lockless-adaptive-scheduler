package io.github.nitesh050.sched.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminationDetectorTest {

    @Test
    void doneOnlyWhenEveryCreatedTaskCompleted() {
        TerminationDetector d = new TerminationDetector();
        d.taskCreated();            // parent
        d.taskCreated();            // child, created while the parent runs
        d.taskCompleted();          // parent finishes first
        assertFalse(d.isDone(), "the child is still outstanding");
        d.taskCompleted();
        assertTrue(d.isDone());
        assertEquals(0, d.outstanding());
        assertTrue(d.doneNanos() > 0);
    }

    @Test
    void creatingAfterTerminationIsABug() {
        TerminationDetector d = new TerminationDetector();
        d.taskCreated();
        d.taskCompleted();
        assertThrows(IllegalStateException.class, d::taskCreated);
    }

    @Test
    void completingMoreThanCreatedIsABug() {
        TerminationDetector d = new TerminationDetector();
        d.taskCreated();
        d.taskCreated();
        d.taskCompleted();
        d.taskCompleted();
        assertThrows(IllegalStateException.class, d::taskCompleted);
    }
}
