# Memory model: from x86 TSO to the JVM

> Owner: A (queues) and B (steal handshake). Fill this in as each lock-free structure is
> written. Every fence or VarHandle access mode in the code should be explained here.

## Why this note exists

X-OpenMP's queues rely on x86 TSO: stores become visible in program order, and plain loads
are not reordered with other loads. Java gives no such guarantee. The JIT may reorder plain
field accesses, and the JVM must also run on weakly ordered CPUs such as ARM. Our
development machines are Apple Silicon (ARM), so the stress tests run on hardware that is
*more* likely than x86 to expose a missing fence.

## Rules we follow

| Situation | Access mode | Why |
|---|---|---|
| SPSC producer publishes a slot, then the tail | plain write of slot, `setRelease` tail | consumer must see the slot contents once it sees the new tail |
| SPSC consumer reads the tail, then the slot | `getAcquire` tail, plain read of slot | pairs with the release above |
| Counter read by another thread (size, stats) | `getOpaque` / `getAcquire` | approximate is fine, but it must not be hoisted out of a loop |
| Steal request cell | `compareAndSet` (volatile) | several thieves race for one victim |
| Mode flag | `volatile` | read once per loop iteration; rare writes |
| TCB state | `compareAndSet` | exactly-once claim |

## Per-structure notes

### SpscQueue

`sched-queue/.../SpscQueue.java` is a ring buffer with two `long` indices that only grow:
`tail`, written only by the producer, and `head`, written only by the consumer. Because each
index has a single writer, no compare-and-swap is needed anywhere.

```
offer (producer)                         poll (consumer)
  t = tail            (plain, own index)   h = head            (plain, own index)
  if t - headCache >= cap:                 if h >= tailCache:
      headCache = head (ACQUIRE) ◄───┐         tailCache = tail (ACQUIRE) ◄───┐
      if still full: return false    │         if still empty: return null    │
  buffer[t & mask] = e (plain)       │     e = buffer[h & mask] (plain)       │
  tail = t + 1        (RELEASE) ─────┼───► buffer[h & mask] = null (plain)    │
                                     └──── head = h + 1      (RELEASE) ───────┘
```

The two release/acquire pairs, and what each prevents:

1. **Producer's tail release → consumer's tail acquire.** If the consumer sees the new tail,
   it also sees the slot write and every write the producer made before offering, such as
   the fields of the task object. Without this, ARM (or the JIT) may make the tail visible
   before the slot, and the consumer reads `null` or a half-initialised task. The jcstress
   test `SpscQueueStress.SafePublication` checks exactly this, using a non-final field so
   that final-field semantics can't hide the bug.
2. **Consumer's head release → producer's head acquire.** If the producer sees the slot as
   free, the consumer has finished reading and clearing it. Without this, the producer could
   write a new element into a slot the consumer is still reading, so one task is lost and
   another is seen twice. `SpscQueueStress.FullBoundary` checks this.

Reading your **own** index is a plain read, because nothing else writes it. `headCache` and
`tailCache` are plain fields private to one side; they only go stale, and stale values are
safe. A stale `headCache` makes the queue look fuller than it is, so the producer re-reads.
A stale `tailCache` makes it look emptier, so the consumer re-reads.

**Why x86 doesn't need this but we do.** Under TSO, stores are not reordered with other
stores, and loads are not reordered with other loads. So "write slot, then write tail" and
"read tail, then read slot" are already ordered in hardware. On x86, Java's
`setRelease`/`getAcquire` compile to plain moves, and only prevent compiler reordering. On
ARM they become `stlr`/`ldar` instructions. The same Java code is therefore correct on both,
and costs nothing extra on x86.

**`size()` from a third thread** (the adaptive monitor) reads `head`, then `tail`, both with
acquire. `tail` only grows, so reading it second can only overestimate. The result is
clamped to `[0, capacity]` and documented as approximate.

**False sharing.** `head`, which the consumer writes, and `tail`, which the producer writes,
are kept 128 bytes apart by a chain of padding superclasses. 128 bytes is one cache line on
Apple Silicon. Otherwise every offer would invalidate the consumer's cached copy of `head`,
and every poll the producer's copy of `tail`.

### StealRequestCell

_TODO (B)_

### ModeFlag

`ModeFlag` wraps an `AtomicReference<Mode>`. Workers do one volatile read per loop iteration,
and the controller writes with `getAndSet`. Nothing here needs ordering with other memory:
the mode is a hint that workers act on at their next decision point. Correctness during a
switch comes from the strategy invariant (tasks only ever move from queue to queue), not from
the flag. A volatile read is a plain load on x86 and an `ldar` on ARM, which is cheap enough
to do on every iteration.
