package org.bikesched.internal.database;

import java.io.Serial;
import java.util.UUID;
import org.bikesched.BikeschedException;

/**
 * Thrown by step writes when the job is no longer {@code RUNNING} under the writing attempt's claim
 * ({@link JobLock}): it was released (e.g. after missed heartbeats) and may already run elsewhere,
 * or in a newer attempt on the same node. Ends the handler of the stale attempt instead of letting
 * it change steps that a newer claim now owns.
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
            + " is no longer running in this attempt's claim by node "
            + nodeId
            + " (it was released, e.g. after missed heartbeats, and may run elsewhere or in a newer"
            + " attempt); stopping this attempt");
  }
}
