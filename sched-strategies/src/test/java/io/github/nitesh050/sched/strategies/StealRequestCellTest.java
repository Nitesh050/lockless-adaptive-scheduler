package io.github.nitesh050.sched.strategies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StealRequestCellTest {

    @Test
    void packsRoundAndThief() {
        long w = StealRequestCell.pack(7, 3);
        assertEquals(7, StealRequestCell.roundOf(w));
        assertEquals(3, StealRequestCell.thiefOf(w));
        assertEquals(StealRequestCell.NO_THIEF, StealRequestCell.thiefOf(StealRequestCell.pack(7, StealRequestCell.NO_THIEF)));
    }

    @Test
    void roundWrapsWithoutCorruptingTheThief() {
        long w = StealRequestCell.pack(0xFFFF_FFFFL + 1, 5);
        assertEquals(0, StealRequestCell.roundOf(w));
        assertEquals(5, StealRequestCell.thiefOf(w));
    }

    @Test
    void postAnswerCycle() {
        StealRequestCell c = new StealRequestCell(3);
        long req = c.post(0, 2);
        assertNotEquals(0, req);
        assertEquals(req, c.pending(0));
        assertFalse(c.isAnswered(0, req));

        assertTrue(c.answer(0, req));
        assertTrue(c.isAnswered(0, req));
        assertEquals(0, c.pending(0));
        assertFalse(c.withdraw(0, req), "cannot withdraw an answered request");
    }

    @Test
    void onlyOneRequestPerVictimAtATime() {
        StealRequestCell c = new StealRequestCell(3);
        assertNotEquals(0, c.post(0, 1));
        assertEquals(0, c.post(0, 2), "victim already has a request");
    }

    @Test
    void withdrawFreesTheCellAndBlocksALateAnswer() {
        StealRequestCell c = new StealRequestCell(3);
        long req = c.post(0, 1);
        assertTrue(c.withdraw(0, req));
        assertEquals(0, c.pending(0));
        assertFalse(c.answer(0, req), "victim must see the withdrawal");
        assertNotEquals(0, c.post(0, 2), "cell is free for the next thief");
    }

    @Test
    void anAnswerCannotBeConfusedWithTheNextRequestFromTheSameThief() {
        StealRequestCell c = new StealRequestCell(2);
        long first = c.post(0, 1);
        c.answer(0, first);
        long second = c.post(0, 1);
        assertNotEquals(first, second, "the round number must differ");
        assertTrue(c.isAnswered(0, first));
        assertFalse(c.isAnswered(0, second));
    }
}
