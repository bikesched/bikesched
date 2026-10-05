package org.bikesched.internal.job;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One of a node's concurrency slots, held by a job from its claim until its attempt ends. Released
 * at most once: by the job's thread when the attempt ends, or earlier by the {@link HardTimeout} if
 * the attempt is abandoned (its thread then returns later without releasing it again).
 */
public final class JobSlot {

  private final Semaphore slots;
  private final AtomicBoolean released = new AtomicBoolean();

  /**
   * @param slots the node's slots, one of which was acquired for this job
   */
  public JobSlot(Semaphore slots) {
    this.slots = Objects.requireNonNull(slots, "slots");
  }

  /** Releases the slot; does nothing if it was released before. */
  public void release() {
    if (released.compareAndSet(false, true)) {
      slots.release();
    }
  }
}
