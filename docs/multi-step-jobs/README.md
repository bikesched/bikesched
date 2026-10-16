---
title: "Multi-step jobs"
weight: 3
---

# Multi-step jobs

A job can do more than one thing in one go: its handler runs durable steps, waits for events and
starts child jobs, and bikesched remembers how far it got. When the job is retried or resumed, the
handler runs again from the top, completed steps return their stored results, and a wait that is
not over yet suspends the job without holding a thread. All of this stays inside a single job, so
there is no process model to deploy next to your code.

- [Steps and compensation](steps.md): durable steps, sleeping, polling an external service, and
  compensations that undo completed steps when the job is cancelled or fails (sagas)
- [Events](events.md): waiting for and publishing events, timeouts and retention
- [Child jobs](child-jobs.md): starting and waiting for child jobs, job results
