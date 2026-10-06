// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import org.bikesched.event.EventWait;
import org.bikesched.event.TimeoutHandler;
import org.bikesched.event.TypedEventWait;

/**
 * The {@link TypedEventWait} implementation: an {@link EventWait} receiving the payload as the
 * event type's payload class.
 *
 * @param <T> the payload type
 */
final class DefaultTypedEventWait<T> implements TypedEventWait<T> {

  private final EventWait wait;
  private final Class<T> payloadType;
  private Duration timeout;
  private TimeoutHandler<? extends T> timeoutHandler;

  DefaultTypedEventWait(EventWait wait, Class<T> payloadType) {
    this.wait = wait;
    this.payloadType = payloadType;
  }

  @Override
  public TypedEventWait<T> timeout(Duration timeout, TimeoutHandler<? extends T> handler) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.timeoutHandler = Objects.requireNonNull(handler, "handler");
    return this;
  }

  @Override
  public T receive() throws Exception {
    if (timeout == null) {
      return wait.receive(payloadType);
    }
    TimeoutHandler<? extends T> handler = timeoutHandler;
    return wait.<T>timeout(timeout, handler::handle).receive(payloadType);
  }
}
