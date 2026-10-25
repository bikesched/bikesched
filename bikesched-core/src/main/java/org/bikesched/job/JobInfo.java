// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
   * Returns the job name, which selects the handler that runs the job.
   *
   * @return the job name
   */
  String name();

  /**
   * Returns how the job was started: on its own, as an occurrence of a recurring job or as a child
   * job.
   *
   * @return the job's type
   */
  JobType type();

  /**
   * Returns the job's reference to an external entity, see {@link JobSettings#reference(String)}.
   *
   * @return the reference; {@code null} if the job has none
   */
  @Nullable String reference();

  /**
   * Returns the job's state when it was read.
   *
   * @return the state
   */
  JobState state();

  /**
   * Returns the job's result (see {@link Job#complete(Object)}), mapped by the configured {@code
   * JsonMapper}.
   *
   * @param type the type to map the result to
   * @param <T> the type of the result
   * @return the result; empty unless the job {@link JobState#SUCCEEDED succeeded} with a result
   */
  <T> Optional<T> result(Class<T> type);

  /**
   * Returns the job that started this one as a child (see {@link Job#newJob(String)}).
   *
   * @return the parent's id; empty if this job is not a child
   */
  Optional<UUID> parentJobId();
}
