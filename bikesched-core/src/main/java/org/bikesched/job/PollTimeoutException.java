// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.io.Serial;
import java.time.Duration;
import org.bikesched.BikeschedException;

/**
 * Thrown by {@link PollBuilder#run(PollHandler)} when a poll with a {@link
 * PollBuilder#timeout(Duration) timeout} was not ready by its deadline. The job then fails without
 * further attempts ({@code FAILED}, with this exception's message as {@code last_error}), even if
 * the handler catches this exception: retrying would time out again.
 *
 * <p>To react to a timeout instead, give the poll a {@link
 * PollBuilder#onFailure(StepFailureHandler) failure handler}; its {@code error()} is this
 * exception's {@code toString()}.
 */
public final class PollTimeoutException extends BikeschedException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the detail message
   */
  public PollTimeoutException(String message) {
    super(message);
  }
}
