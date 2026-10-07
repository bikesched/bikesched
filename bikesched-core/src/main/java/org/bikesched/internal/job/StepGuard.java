// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps steps from nesting: while a step handler or a compensation of a job runs, the job rejects
 * steps and event waits with an {@link IllegalStateException}, from any thread. An inner step would
 * complete durably even if the outer one failed (a retry then runs the outer step again but skips
 * the inner one), and a wait would suspend the job while the outer step is still running.
 */
final class StepGuard {

  private final UUID jobId;

  /** The running step handler or compensation, e.g. {@code step 'charge'}; {@code null} if none. */
  private final AtomicReference<String> running = new AtomicReference<>();

  StepGuard(UUID jobId) {
    this.jobId = jobId;
  }

  /**
   * Checks that no step handler or compensation runs.
   *
   * @param action what the job was asked to do, e.g. {@code run step 'mail'}
   * @throws IllegalStateException if a step handler or compensation runs
   */
  void requireNoneRunning(String action) {
    String current = running.get();
    if (current != null) {
      throw nested(action, current);
    }
  }

  /**
   * Runs a step handler or compensation, rejecting steps and event waits until it returns.
   *
   * @param description the handler, e.g. {@code step 'charge'}
   * @throws IllegalStateException if another step handler or compensation runs
   */
  <R> R run(String description, DefaultJob.StepCall<R> call) throws Exception {
    String current = running.compareAndExchange(null, description);
    if (current != null) {
      throw nested("run " + description, current);
    }
    try {
      return call.call();
    } finally {
      running.set(null);
    }
  }

  private IllegalStateException nested(String action, String current) {
    return new IllegalStateException(
        "Job "
            + jobId
            + " cannot "
            + action
            + " while "
            + current
            + " runs: steps and event waits cannot be nested");
  }
}
