package org.bikesched.job;

import java.util.UUID;

/**
 * A read-only snapshot of a job, see {@code Bikesched.findJob(UUID)}: its identity and state at the
 * time it was read.
 */
public interface JobInfo {

  /**
   * Returns the job id.
   *
   * @return the job id
   */
  UUID id();

  /**
   * Returns the job type.
   *
   * @return the job type
   */
  String type();

  /**
   * Returns the job's state when it was read.
   *
   * @return the state
   */
  JobState state();
}
