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

_TODO (A)_

### StealRequestCell

_TODO (B)_

### ModeFlag

_TODO (A)_
