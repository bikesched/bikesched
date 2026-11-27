// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A started batch of child jobs, see {@link Job#newJobBatch(String, ResultJob)}: the handle its
 * parent waits with. The same on every run of the parent.
 *
 * @param <R> the result type of the items ({@code Object} for a mixed batch or a job given by its
 *     name, {@code Void} for a job without a result type)
 */
public interface JobBatch<R extends @Nullable Object> {

  /**
   * Returns the name of the batch's step.
   *
   * @return the step name given to {@code newJobBatch}
   */
  String stepName();

  /**
   * Returns the items' child jobs by key, in item order.
   *
   * @return key to child job id
   */
  Map<String, UUID> jobIds();

  /**
   * Waits until every item has ended (or, in a fail-fast batch, the first one did not succeed).
   * Suspends the parent without holding a thread until then, like {@link Job#waitForJob(UUID,
   * Class)}; the parent resumes once, when the batch has ended.
   *
   * @return the outcome of every item
   * @throws IllegalStateException if called inside a step's handler
   */
  JobBatchResult<R> await();

  /**
   * Waits like {@link #await()}, at most for {@code timeout}, counted from the first wait (the
   * stored deadline wins on later runs). At the deadline the unfinished items are {@link
   * JobResult.TimedOut} and the held ones are cancelled; running items keep running.
   *
   * @param timeout a positive duration
   * @return the outcome of every item
   */
  JobBatchResult<R> await(Duration timeout);
}
