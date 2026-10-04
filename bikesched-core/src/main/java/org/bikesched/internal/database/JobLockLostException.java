package org.bikesched.internal.database;

import java.io.Serial;
import java.util.UUID;
import org.bikesched.BikeschedException;

/**
 * Thrown by step writes when the job is no longer {@code RUNNING} on the writing node: it was
 * released (e.g. after missed heartbeats) and may already run elsewhere. Ends the handler of the
 * stale attempt instead of letting it change steps another node now owns.
 */
public final class JobLockLostException extends BikeschedException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param jobId the job
   * @param nodeId the node that lost it
   */
  public JobLockLostException(UUID jobId, UUID nodeId) {
    super(
        "Bikesched job "
            + jobId
            + " is no longer running on node "
            + nodeId
            + " (it was released, e.g. after missed heartbeats); stopping this attempt");
  }
}
