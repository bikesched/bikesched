// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import java.time.Duration;
import org.bikesched.BikeschedBuilder;
import org.bikesched.BikeschedException;
import org.bikesched.JsonMapper;
import org.bikesched.job.FailingStep;
import org.bikesched.job.StepFailureHandler;

/**
 * A wait for an event as a durable step of a job, obtained via {@link
 * org.bikesched.job.Job#waitForEvent(String, String, String, Class)} or {@link
 * org.bikesched.job.Job#waitForEvent(String, EventType, String)}: an optional timeout and failure
 * handler, then {@link #receive()}:
 *
 * <pre>{@code
 * Payment payment = job.waitForEvent("await-payment", "payment-received", order.id(), Payment.class)
 *         .timeout(Duration.ofDays(3))                               // optional
 *         .onFailure(failing -> failing.resolve(Payment.MISSING))    // optional
 *         .receive();
 * }</pre>
 *
 * <p>Like a step, the wait stores its result: the received event's payload is copied into the step,
 * and later runs of the job's handler return it, even after the event itself was deleted. {@code
 * receive} returns right away if the event was already received (in this or an earlier run of the
 * job's handler) or was published before; otherwise the job's attempt ends there (the method does
 * not return) and the job is suspended until the event is published or the timeout passed. Not
 * thread-safe.
 *
 * @param <T> the payload type
 */
public interface EventWait<T> {

  /**
   * Limits the wait (optional): once the given time, counted from when the step first started
   * waiting, has passed without the event, the wait fails with an {@link EventTimeoutException}.
   * That fails the job without further attempts, unless a {@link #onFailure(StepFailureHandler)
   * failure handler} resolves the wait. Without it, the node's {@link
   * BikeschedBuilder#defaultEventTimeout(Duration) default event timeout} applies, if any.
   *
   * <p>The deadline stored when the step first waited wins: a changed timeout on later runs does
   * not move it.
   *
   * @param timeout how long to wait at most, positive
   * @return this wait
   * @throws IllegalArgumentException if the timeout is not positive
   */
  EventWait<T> timeout(Duration timeout);

  /**
   * Sets a handler that runs once the wait timed out (optional), by its own {@link
   * #timeout(Duration) timeout} or the node's default event timeout, before the step and the job
   * are recorded as failed. The handler may {@linkplain FailingStep#resolve(Object) resolve} the
   * wait with a fallback result instead, which {@link #receive()} returns (stored like a received
   * payload), so the job continues. It runs right away, whatever attempt of the job reached the
   * timeout: waiting again would time out again. The step is {@code FAILING} meanwhile; a handler
   * that throws runs again in an extra attempt of the job, up to the node's {@link
   * BikeschedBuilder#failureHandlerAttempts(int) failureHandlerAttempts} runs. Like every failure
   * handler, it cannot run steps, waits or polls.
   *
   * @param handler the failure handler, must be idempotent
   * @return this wait
   */
  EventWait<T> onFailure(StepFailureHandler<T> handler);

  /**
   * Waits for the event and returns its payload converted by the {@link JsonMapper}; the payload is
   * stored with the step as it is. Once the wait timed out, returns the fallback result its failure
   * handler resolved it with.
   *
   * @return the payload or the fallback result, may be {@code null}
   * @throws EventTimeoutException if the wait timed out and no failure handler resolved it; the job
   *     fails
   * @throws IllegalStateException if the step name is used for another step of the job, or if
   *     called while a step handler or compensation of the job runs
   * @throws BikeschedException if the step cannot be read or stored
   */
  T receive();
}
