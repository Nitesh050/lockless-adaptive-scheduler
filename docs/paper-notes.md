# Paper notes

## X-OpenMP

_TODO: summary, XQueue design, the three strategies, the task-distribution delta formula,
which benchmark favoured which strategy, and where our reimplementation differs._

### Open questions to check against the paper (from the Phase 2 implementation)

- **Steal-1 / steal-2.** We implemented these as "the victim hands over up to 1 or 2 tasks per
  request" (`NoWaitSteal` `batch`). If the paper means something else, such as number of
  victims tried, adjust `NoWaitSteal` and log the change.
- **Task-distribution delta.** `DeltaCalculator` reports (max − min) / mean and the
  coefficient of variation. Add the paper's exact formula as `paperDelta` and use it in the
  report.
- **Victim selection.** We sample up to 4 random workers and take the first that looks
  non-empty. Check whether the paper uses purely random victims, round-robin victims, or
  something else.
- **Where victims check for requests.** We check on spawn (enqueue) and on dequeue, and idle
  workers decline at once. Confirm against the paper's description.

## Related work on runtime adaptation

_TODO: WS-DLB, HotSLAW, and how our switching differs from what they do._
