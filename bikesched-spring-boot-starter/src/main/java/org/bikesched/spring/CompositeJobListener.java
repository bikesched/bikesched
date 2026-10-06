// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.util.List;
import java.util.Objects;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobClaim;
import org.bikesched.job.listener.JobListener;

/**
 * Passes every event to all {@link JobListener} beans of the context: the builder takes one
 * listener, so the starter composes the beans into one. Listeners are called in the order they were
 * resolved, i.e. their {@code @Order}.
 */
final class CompositeJobListener implements JobListener {

  private final List<JobListener> listeners;

  CompositeJobListener(List<JobListener> listeners) {
    this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
  }

  /** The number of composed listeners, for the startup log. */
  int size() {
    return listeners.size();
  }

  @Override
  public void onJobClaimed(JobClaim claim) {
    listeners.forEach(listener -> listener.onJobClaimed(claim));
  }

  @Override
  public void onAttemptEnded(JobAttemptResult result) {
    listeners.forEach(listener -> listener.onAttemptEnded(result));
  }

  @Override
  public void onAbandonedAttemptReturned(JobClaim claim) {
    listeners.forEach(listener -> listener.onAbandonedAttemptReturned(claim));
  }
}
