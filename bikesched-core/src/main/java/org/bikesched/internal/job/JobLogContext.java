// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import org.bikesched.BikeschedLogContext;
import org.bikesched.job.Job;
import org.slf4j.MDC;

/**
 * The node and job in the logging context ({@link BikeschedLogContext}) while code runs for a job:
 * {@code try (var _ = JobLogContext.open(nodeId, job)) { … }}. Closing removes the entries.
 */
public final class JobLogContext implements AutoCloseable {

  private JobLogContext() {}

  /**
   * Puts the node id, job id and job type into the logging context.
   *
   * @param nodeId the node running the job
   * @param job the job
   * @return the context, removing the entries when closed
   */
  public static JobLogContext open(Object nodeId, Job job) {
    MDC.put(BikeschedLogContext.NODE_ID, String.valueOf(nodeId));
    MDC.put(BikeschedLogContext.JOB_ID, job.id().toString());
    MDC.put(BikeschedLogContext.JOB_TYPE, job.type());
    return new JobLogContext();
  }

  @Override
  public void close() {
    MDC.remove(BikeschedLogContext.NODE_ID);
    MDC.remove(BikeschedLogContext.JOB_ID);
    MDC.remove(BikeschedLogContext.JOB_TYPE);
  }
}
