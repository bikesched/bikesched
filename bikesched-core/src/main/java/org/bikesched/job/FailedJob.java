// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A job that failed permanently, handed to its {@link JobFailureHandler}. */
public interface FailedJob {

  /**
   * Returns the job id.
   *
   * @return the id
   */
  UUID id();

  /**
   * Returns the job name.
   *
   * @return the name
   */
  String name();

  /**
   * Returns the job's reference to an external entity, see {@link Job#reference()}.
   *
   * @return the reference; empty if the job has none
   */
  Optional<String> reference();

  /**
   * Returns the payload as stored, see {@link Job#payload()}.
   *
   * @return the payload as JSON text, {@code null} if the job has none
   */
  @Nullable String payload();

  /**
   * Returns the payload mapped by the configured {@code JsonMapper}, see {@link
   * Job#payload(Class)}.
   *
   * @param type the payload class
   * @param <T> the payload type
   * @return the payload, {@code null} if the job has none
   */
  <T extends @Nullable Object> @Nullable T payload(Class<T> type);

  /**
   * Returns the state the job ends in once the handler returned.
   *
   * @return {@link JobState#FAILED} or {@link JobState#COMPENSATION_FAILED}
   */
  JobState state();

  /**
   * Returns the description of the failure stored with the job (its {@code last_error}, e.g. the
   * exception's {@code toString()}, truncated).
   *
   * @return the stored error, {@code null} if none was stored
   */
  @Nullable String error();
}
