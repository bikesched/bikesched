// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.io.Serial;

/**
 * Thrown by a step or event wait that has not completed while the handler runs to compensate its
 * job: the replay ends there, and the compensations of the steps completed before run.
 *
 * <p>An {@link Error} rather than an exception, so a {@code catch (Exception e)} in the handler
 * does not swallow it by accident. Created without a stack trace: it is control flow, not a
 * failure.
 */
final class CompensationReplayEnded extends Error {

  @Serial private static final long serialVersionUID = 1L;

  CompensationReplayEnded(String stepName) {
    super(
        "Compensation replay ended at step '" + stepName + "', which has not completed",
        null,
        false,
        false);
  }
}
