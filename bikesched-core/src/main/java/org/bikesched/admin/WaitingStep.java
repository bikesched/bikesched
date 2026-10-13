// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An event wait still waiting, see {@link Admin#findWaitingSteps(String, String, int)}.
 *
 * @param jobId the step's job
 * @param jobType the job's type
 * @param stepName the step name
 * @param eventName the event name it waits for
 * @param eventKey the event key it waits for
 * @param since when it started waiting
 * @param timeoutAt when it times out; {@code null} if it waits without a deadline
 */
public record WaitingStep(
    UUID jobId,
    String jobType,
    String stepName,
    String eventName,
    String eventKey,
    Instant since,
    @Nullable Instant timeoutAt) {}
