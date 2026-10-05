package org.bikesched.internal.database;

import java.util.Objects;
import java.util.UUID;

/**
 * The lock of a claimed job: the claiming node and the token of this claim. Every write of the
 * claim's attempt (ending the job, suspending it, its steps) only takes effect while the job is
 * still {@code RUNNING} with this token, so a stale attempt cannot write once the job was released,
 * even if the same node has claimed it again since. The node id is recorded in the job history.
 *
 * @param nodeId the node that claimed the job
 * @param token the token of the claim ({@code bikesched_jobs.lock_token}), new for every claim
 */
public record JobLock(UUID nodeId, UUID token) {

  /** Validates the lock. */
  public JobLock {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(token, "token");
  }
}
